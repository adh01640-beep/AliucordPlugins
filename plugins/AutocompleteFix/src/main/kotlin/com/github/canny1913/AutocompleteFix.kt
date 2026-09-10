package com.github.canny1913

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.utils.ChannelUtils
import com.aliucord.utils.accessField
import com.aliucord.wrappers.ChannelWrapper.Companion.id
import com.aliucord.wrappers.GuildRoleWrapper.Companion.name
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandAutocompletable
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandChoiceAutocompletable
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandLoadingPlaceholder
import com.discord.widgets.chat.input.autocomplete.ApplicationPlaceholder
import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.AutocompletableKt
import com.discord.widgets.chat.input.autocomplete.ChannelAutocompletable
import com.discord.widgets.chat.input.autocomplete.`ChatInputAutocompletables$observeChannelAutocompletables$1$1`
import com.discord.widgets.chat.input.autocomplete.EmojiAutocompletable
import com.discord.widgets.chat.input.autocomplete.EmojiUpsellPlaceholder
import com.discord.widgets.chat.input.autocomplete.GlobalRoleAutocompletable
import com.discord.widgets.chat.input.autocomplete.RoleAutocompletable
import com.discord.widgets.chat.input.autocomplete.UserAutocompletable
import java.util.TreeMap
import java.util.TreeSet
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

@AliucordPlugin(
    requiresRestart = true
)
class AutocompleteFix : Plugin() {

    private var TreeSet<*>.map by accessField<TreeMap<*, *>>("m")
    private var TreeMap<*, *>.comparator by accessField<Comparator<*>>()

    override fun start(context: Context) {
        patcher.after<`ChatInputAutocompletables$observeChannelAutocompletables$1$1`<*, *, *, *, *>>(
            "call",
            Map::class.java,
            Map::class.java,
            Map::class.java,
            Map::class.java
        ) { param ->
            val result = param.result as Map<*, TreeSet<Autocompletable>>
            result.keys.forEach {
                val treeSet = result[it] ?: return@forEach
                treeSet.map.comparator = AutocompletableComparator()
            }
        }
    }

    override fun stop(context: Context) = patcher.unpatchAll()
}

@Suppress("unused")
class AutocompletableComparator : Comparator<Autocompletable> {
    override fun compare(a: Autocompletable, b: Autocompletable): Int {
        if (a::class != b::class) {
            return AutocompletableKt.getSortIndex(a).compareTo(AutocompletableKt.getSortIndex(b))
        }

        return when {
            check<ApplicationCommandChoiceAutocompletable>(a, b) -> {
                compareValuesBy(
                    a, b,
                    { it.choice.a().lowercase() },
                    { it.choice.b()?.toString() },
                    { System.identityHashCode(it) }
                )
            }

            check<ApplicationCommandAutocompletable>(a, b) -> {
                compareValuesBy(
                    a, b,
                    { it.command.name },
                    { it.application?.id },
                    { System.identityHashCode(it) }
                )
            }

            check<ApplicationPlaceholder>(a, b) -> {
                compareValuesBy(a, b) { it.application.name.lowercase() }
            }

            check<ChannelAutocompletable>(a, b) -> {
                compareValuesBy(
                    a, b,
                    { ChannelUtils.getDisplayName(it.channel).lowercase() },
                    { it.channel.id }
                )
            }

            check<EmojiAutocompletable>(a, b) -> {
                compareValuesBy(
                    a, b,
                    { it.emoji.firstName },
                    { System.identityHashCode(it) }
                )
            }

            check<GlobalRoleAutocompletable>(a, b) -> {
                compareValuesBy(
                    a, b,
                    { it.text.lowercase() },
                    { System.identityHashCode(it) }
                )
            }

            check<RoleAutocompletable>(a, b) -> {
                compareValuesBy(
                    a, b,
                    { it.role.name.lowercase() },
                    { it.role.id }
                )
            }

            check<UserAutocompletable>(a, b) -> {
                compareValuesBy(
                    a, b,
                    { (it.nickname ?: it.user.username).lowercase() },
                    { it.user.username.lowercase() },
                    { it.user.discriminator },
                    { it.user.id }
                )
            }

            check<ApplicationCommandLoadingPlaceholder>(a, b) -> 0
            check<EmojiUpsellPlaceholder>(a, b) -> 0

            else -> throw NoWhenBranchMatchedException()
        }
    }

    @OptIn(ExperimentalContracts::class)
    private inline fun <reified T : Autocompletable> check(a: Autocompletable, b: Autocompletable): Boolean {
        contract {
            returns(true) implies (a is T)
            returns(true) implies (b is T)
        }
        return a is T
    }
}
                    { System.identityHashCode(it) }
                )
            }

            // حل مشكلة منشن الرتب العادية (@Role) في الشات
            check<RoleAutocompletable>(a, b) -> {
                compareValuesBy(
                    a, b,
                    { it.role.name.lowercase() },
                    { it.role.id }
                )
            }

            // حل مشكلة منشن الحسابات المتطابقة في الشات (@User)
            check<UserAutocompletable>(a, b) -> {
                compareValuesBy(
                    a, b,
                    { (it.nickname ?: it.user.username).lowercase() },
                    { it.user.username.lowercase() },
                    { it.user.discriminator },
                    { it.user.id }
                )
            }

            check<ApplicationCommandLoadingPlaceholder>(a, b) -> 0
            check<EmojiUpsellPlaceholder>(a, b) -> 0

            else -> throw NoWhenBranchMatchedException()
        }
    }

    @OptIn(ExperimentalContracts::class)
    private inline fun <reified T : Autocompletable> check(a: Autocompletable, b: Autocompletable): Boolean {
        contract {
            returns(true) implies (a is T)
            returns(true) implies (b is T)
        }
        return a is T
    }
}

            check<UserAutocompletable>(a, b) -> {
                compareValuesBy(
                    a, b,
                    { (it.nickname ?: it.user.username).lowercase() },
                    { it.user.username.lowercase() },
                    { it.user.discriminator },
                    { it.user.id }
                )
            }

            check<ApplicationCommandLoadingPlaceholder>(a, b) -> 0
            check<EmojiUpsellPlaceholder>(a, b) -> 0

            else -> throw NoWhenBranchMatchedException()
        }
    }

    @OptIn(ExperimentalContracts::class)
    private inline fun <reified T : Autocompletable> check(a: Autocompletable, b: Autocompletable): Boolean {
        contract {
            returns(true) implies (a is T)
            returns(true) implies (b is T)
        }
        return a is T
    }
}
