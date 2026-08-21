package org.openquestcamera.app;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

final class AppLocale {
    static final String PREFS = "openquestcamera_0_1";
    private static final String KEY_LANGUAGE = "language";
    private static final String DEFAULT_LANGUAGE = "en";
    private static final String[] LANGUAGE_TAGS = new String[]{
            "en", "ja", "de", "fr", "es", "ko", "it", "nl", "pl", "pt-BR",
            "zh-CN", "zh-TW", "sv", "nb", "da", "cs", "ru", "tr", "th", "id"
    };

    static Context wrap(Context base) {
        String languageTag = selectedLanguageTag(base);
        Locale locale = Locale.forLanguageTag(languageTag);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(locale));
        configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }

    static String selectedLanguageTag(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = (LocaleManager) context.getSystemService(Context.LOCALE_SERVICE);
            if (manager != null && !manager.getApplicationLocales().isEmpty()) {
                String systemTag = supportedTag(manager.getApplicationLocales().get(0).toLanguageTag());
                if (systemTag != null) return systemTag;
            }
        }
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = supportedTag(preferences.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE));
        return stored == null ? DEFAULT_LANGUAGE : stored;
    }

    static void apply(Activity activity, String languageTag) {
        String supported = supportedTag(languageTag);
        if (supported == null) supported = DEFAULT_LANGUAGE;
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, supported).commit();
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = (LocaleManager) activity.getSystemService(Context.LOCALE_SERVICE);
            if (manager != null) {
                LocaleList locales = LocaleList.forLanguageTags(supported);
                if (!manager.getApplicationLocales().toLanguageTags().equals(locales.toLanguageTags())) {
                    manager.setApplicationLocales(locales);
                    return;
                }
            }
        }
        activity.recreate();
    }

    static int languageCount() {
        return LANGUAGE_TAGS.length;
    }

    static String languageTagAt(int position) {
        return position >= 0 && position < LANGUAGE_TAGS.length ? LANGUAGE_TAGS[position] : DEFAULT_LANGUAGE;
    }

    static int languagePosition(String languageTag) {
        String supported = supportedTag(languageTag);
        for (int i = 0; i < LANGUAGE_TAGS.length; i++) {
            if (LANGUAGE_TAGS[i].equals(supported)) return i;
        }
        return 0;
    }

    private static String supportedTag(String languageTag) {
        if (languageTag == null) return null;
        for (String supported : LANGUAGE_TAGS) {
            if (supported.equalsIgnoreCase(languageTag)) return supported;
        }
        return null;
    }

    private AppLocale() {}
}
