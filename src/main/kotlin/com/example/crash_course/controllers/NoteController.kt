package com.example.crash_course.controllers

import com.example.crash_course.database.model.Note
import com.example.crash_course.database.model.NoteRepository
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/notes")
class NoteController(
    private val repository: NoteRepository
) {
    data class NoteRequest(
        val id: String?,
        val color: Long,
        val title: String,
        val content: String,
        val ownerId: String
    )

    data class NoteResponse(
        val id: String,
        val color: Long,
        val title: String,
        val content: String,
        val createdAt: String
    )

    @PostMapping
    fun save(
        @RequestBody body: NoteRequest
    ): NoteResponse {
        val note = repository.save(
             Note(
                id = body.id?.let { ObjectId(it) } ?: ObjectId.get(),
                title = body.title,
                color = body.color,
                content = body.content,
                ownerId = ObjectId(body.ownerId),
                createdAt = Instant.now()
            )
        )

        return  note.toResponse()
    }

    @GetMapping
    fun findByOwnerId(
        @RequestParam(required = true) ownerId: String
    ): List<NoteResponse> {
        return repository.findByOwnerId(ObjectId(ownerId)).map {
            it.toResponse()
        }
    }

    @DeleteMapping("/{id}")
    fun deleteById(@PathVariable id: ObjectId) {
        repository.deleteById(ObjectId(id.toHexString()))
    }

    private fun Note.toResponse(): NoteController.NoteResponse {
        return NoteResponse(
            id = id.toHexString(),
            color = color,
            title = title,
            content = content,
            createdAt = createdAt.toString()
        )
    }
}