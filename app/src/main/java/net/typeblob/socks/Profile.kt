package net.typeblob.socks

data class Profile(
    val name: String = "Default",
    val domain: String = "",
    val resolvers: String = "",
    val port: Int = 1081
)