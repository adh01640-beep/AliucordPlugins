import com.aliucord.gradle.AliucordExtension

version = "1.0.0"

description = "Shows all Discord search filters in Aliucord."

configure<AliucordExtension> {
    // TODO: replace 0L with your real Discord user ID if you want the
    // author name to link to your profile in the plugin list
    author("Adham", 0L, hyperlink = true)
}
