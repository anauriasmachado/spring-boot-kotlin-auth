package com.example.crash_course.database

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration
class MongoConfig(
    @Value("\${spring.data.mongodb.uri}")
    private val mongoUri: String
) {
    @Bean
    @Primary
    fun mongoClient(): MongoClient {
        // Use the connection string URI directly
        return MongoClients.create(mongoUri)
    }
}

