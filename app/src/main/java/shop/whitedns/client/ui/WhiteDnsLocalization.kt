package shop.whitedns.client.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/** Localize resources without replacing the Activity context used by system actions. */
internal fun whiteDnsResources(context: Context, language: String): Resources {
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(language))
    return context.createConfigurationContext(configuration).resources
}
