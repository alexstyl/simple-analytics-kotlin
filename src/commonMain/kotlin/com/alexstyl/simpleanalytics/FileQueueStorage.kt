package com.alexstyl.simpleanalytics

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/** Keeps the queue in [file], replacing it in one step so a crash mid-write never leaves half a queue. */
internal class FileQueueStorage(private val file: Path) : QueueStorage {
    private val temp = Path("$file.tmp")

    override fun read(): String? {
        if (SystemFileSystem.metadataOrNull(file)?.isRegularFile != true) return null
        return SystemFileSystem.source(file).buffered().use { it.readString() }
    }

    override fun write(queue: String) {
        file.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(temp).buffered().use { it.writeString(queue) }
        SystemFileSystem.atomicMove(temp, file)
    }
}
