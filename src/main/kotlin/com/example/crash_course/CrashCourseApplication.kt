package com.example.crash_course

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CrashCourseApplication

fun main(args: Array<String>) {
	runApplication<CrashCourseApplication>(*args)
}
