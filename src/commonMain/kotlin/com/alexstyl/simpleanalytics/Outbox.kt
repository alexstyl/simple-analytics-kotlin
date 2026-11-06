package com.alexstyl.simpleanalytics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Payloads waiting to be sent, oldest first. They're sent one at a time; a failure to reach Simple Analytics pauses the
 * queue until a retry after [retryDelay]. Holds at most [maxQueued] payloads, dropping the oldest, and drops payloads
 * older than [maxAge]. With a [file], the queue is saved after every change and loaded on creation.
 *
 * All state is touched only by [worker], which runs [tasks] one at a time in order.
 */
internal class Outbox(
    private val file: Path?,
    private val post: suspend (String) -> Int,
    private val clock: () -> Instant,
    private val retryDelay: (attempt: Int) -> Duration,
    private val maxQueued: Int,
    private val maxAge: Duration,
) {
    private class Payload(val at: Instant, val body: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("SimpleAnalytics"))
    private val tasks = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val queue = ArrayDeque<Payload>()
    private var retry: Job? = null
    private var failures = 0

    private val worker = scope.launch {
        for (task in tasks) {
            try {
                task()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarning("Simple Analytics queue failed", e)
            }
        }
        // A pending retry shouldn't hold up closing; the payload stays queued.
        retry?.cancel()
    }

    init {
        onWorker { load(); sendQueued() }
    }

    fun add(at: Instant, body: String) = onWorker {
        queue.addLast(Payload(at, body))
        trim()
        save()
        // While waiting for a retry, Simple Analytics is unreachable; the retry sends this too.
        if (retry == null) sendQueued()
    }

    /** Runs the tasks already added, waiting up to [timeout] for them. */
    fun close(timeout: Duration) {
        tasks.close()
        runBlocking { withTimeoutOrNull(timeout) { worker.join() } }
        scope.cancel()
    }

    private fun onWorker(task: suspend () -> Unit) {
        tasks.trySend(task)
    }

    private suspend fun sendQueued() {
        while (true) {
            trim()
            val next = queue.firstOrNull() ?: return
            val status = try {
                post(next.body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logDebug("Couldn't reach Simple Analytics", e)
                null
            }
            if (status == null || status == 408 || status == 429 || status >= 500) {
                failures++
                val delay = retryDelay(failures)
                retry = scope.launch {
                    delay(delay)
                    onWorker { retry = null; sendQueued() }
                }
                return
            }
            if (status !in 200..299) logWarning("Simple Analytics refused ${next.body} with $status")
            failures = 0
            queue.removeFirst()
            save()
        }
    }

    private fun trim() {
        val oldest = clock() - maxAge
        while (queue.isNotEmpty() && (queue.size > maxQueued || queue.first().at < oldest)) queue.removeFirst()
    }

    private fun load() {
        val file = file?.takeIf { SystemFileSystem.metadataOrNull(it)?.isRegularFile == true } ?: return
        val text = runCatching { SystemFileSystem.source(file).buffered().use { it.readString() } }.getOrNull() ?: return
        text.lineSequence().forEach { line ->
            val (at, body) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@forEach
            val time = at.toLongOrNull()?.let(Instant::fromEpochMilliseconds) ?: return@forEach
            queue.addLast(Payload(time, body))
        }
        trim()
    }

    private fun save() {
        val file = file ?: return
        runCatching {
            val directory = file.parent
            directory?.let { SystemFileSystem.createDirectories(it) }
            val temp = if (directory != null) Path(directory, "${file.name}.tmp") else Path("${file.name}.tmp")
            // JSON escapes tabs and newlines, so neither appears inside a body.
            SystemFileSystem.sink(temp).buffered().use { sink ->
                queue.forEach { sink.writeString("${it.at.toEpochMilliseconds()}\t${it.body}\n") }
            }
            SystemFileSystem.atomicMove(temp, file)
        }.onFailure { logWarning("Couldn't save the Simple Analytics queue to $file", it) }
    }
}
