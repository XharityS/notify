package ru.echosystems.notify

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform