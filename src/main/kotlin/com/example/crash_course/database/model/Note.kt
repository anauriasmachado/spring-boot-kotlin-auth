package com.example.crash_course.database.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "notes")
data class Note(
    @Id val id: ObjectId = ObjectId.get(),
    val color: Long,
    val title: String,
    val ownerId: ObjectId,
    val content: String,
    val createdAt: Instant
)
