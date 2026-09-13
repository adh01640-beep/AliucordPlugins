import com.aliucord.gradle.AliucordExtension

version = "1.0.0"

description = "Shows every server role in the @mention autocomplete list, even roles you don't have permission to ping. Each role is labeled (mention) or (silent) depending on whether selecting it will actually notify that role. Toggleable in the plugin settings."

configure<AliucordExtension> {
    // TODO: replace 0L with your real Discord user ID if you want the
    // author name to link to your profile in the plugin list
    author("Adham", 0L, hyperlink = true)
}
