plugins {
    id("com.aliucord.gradle")
    kotlin("android")
}

aliucord {
    author("Adham", 0L)
    changelog.set(
        """
        # v1.0.0
        - Initial release
        """.trimIndent()
    )
    description.set("Send a message and instantly delete or edit it (ghost message).")
}
