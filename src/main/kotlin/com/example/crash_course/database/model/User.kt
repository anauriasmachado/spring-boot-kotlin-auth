package com.example.crash_course.database.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "users")
data class User(
    val email: String,
    val hashedPassword: String,
    @Id val id: ObjectId = ObjectId.get(),
)
