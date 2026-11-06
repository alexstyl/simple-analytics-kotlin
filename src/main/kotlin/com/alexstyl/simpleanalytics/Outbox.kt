package com.alexstyl.simpleanalytics

import java.io.File
import java.lang.System.Logger.Level
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Payloads waiting to be sent, oldest first. They're sent one at a time; a failure to reach Simple Analytics pauses the
 * queue until a retry after [retryDelay]. Holds at most [maxQueued] payloads, dropping the oldest, and drops payloads
 * older than [maxAge]. With a [file], the queue is saved after every change and loaded on creation.
 *
 * All state is touched only on [worker]'s thread.
 */
internal class Outbox(
    private val file: File?,
    private val post: (String) -> Int,
    private val clock: () -> Instant,
    private val retryDelay: (attempt: Int) -> Duration,
    private val maxQueued: Int,
    private val maxAge: Duration,
) {
    private class Payload(val at: Instant, val body: String)

    private val worker = ScheduledThreadPoolExecutor(1) { Thread(it, "SimpleAnalytics").apply { isDaemon = true } }.apply {
        // A pending retry shouldn't hold up closing; the payload stays queued.
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }
    private val queue = ArrayDeque<Payload>()
    private var retry: ScheduledFuture<*>? = null
    private var failures = 0

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

    fun close(timeout: Duration) {
        worker.shutdown()
        runCatching { worker.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS) }
    }

    private fun onWorker(task: () -> Unit) {
        runCatching { worker.execute { runCatching(task).onFailure { logger.log(Level.WARNING, "Simple Analytics queue failed", it) } } }
    }

    private fun sendQueued() {
        while (true) {
            trim()
            val next = queue.firstOrNull() ?: return
            val status = try {
                post(next.body)
            } catch (e: Exception) {
                logger.log(Level.DEBUG, "Couldn't reach Simple Analytics", e)
                null
            }
            if (status == null || status == 408 || status == 429 || status >= 500) {
                failures++
                retry = runCatching { worker.schedule({ retry = null; sendQueued() }, retryDelay(failures).toMillis(), TimeUnit.MILLISECONDS) }.getOrNull()
                return
            }
            if (status !in 200..299) logger.log(Level.WARNING, "Simple Analytics refused ${next.body} with $status")
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
        val lines = file?.takeIf { it.isFile }?.let { runCatching { it.readLines() }.getOrNull() } ?: return
        lines.forEach { line ->
            val (at, body) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@forEach
            val time = at.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return@forEach
            queue.addLast(Payload(time, body))
        }
        trim()
    }

    private fun save() {
        val file = file ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            // JSON escapes tabs and newlines, so neither appears inside a body.
            temp.writeText(queue.joinToString("") { "${it.at.toEpochMilli()}\t${it.body}\n" })
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { logger.log(Level.WARNING, "Couldn't save the Simple Analytics queue to $file", it) }
    }
}
