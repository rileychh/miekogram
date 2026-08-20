package dev.rileychh.nekogram.helpers;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.util.Locale;
import java.util.function.Consumer;

import dev.rileychh.nekogram.settings.BaseNekoSettingsActivity;
import dev.rileychh.nekogram.settings.NekoAppearanceSettingsActivity;
import dev.rileychh.nekogram.settings.NekoChatSettingsActivity;
import dev.rileychh.nekogram.settings.NekoDonateActivity;
import dev.rileychh.nekogram.settings.NekoEmojiSettingsActivity;
import dev.rileychh.nekogram.settings.NekoExperimentalSettingsActivity;
import dev.rileychh.nekogram.settings.NekoGeneralSettingsActivity;
import dev.rileychh.nekogram.settings.NekoPasscodeSettingsActivity;
import dev.rileychh.nekogram.settings.NekoSettingsActivity;

public class SettingsHelper {

    public static void processDeepLink(Uri uri, Consumer<BaseFragment> callback, Runnable unknown, Browser.Progress progress) {
        if (uri == null) {
            unknown.run();
            return;
        }
        var segments = uri.getPathSegments();
        if (segments.isEmpty() || segments.size() > 2) {
            unknown.run();
            return;
        }
        BaseNekoSettingsActivity fragment;
        if (segments.size() == 1) {
            fragment = new NekoSettingsActivity();
        } else {
            var segment = segments.get(1);
            if (PasscodeHelper.getSettingsKey().equals(segment)) {
                fragment = new NekoPasscodeSettingsActivity();
            } else {
                switch (segment.toLowerCase(Locale.US)) {
                    case "appearance":
                    case "a":
                        fragment = new NekoAppearanceSettingsActivity();
                        break;
                    case "chat":
                    case "chats":
                    case "c":
                        fragment = new NekoChatSettingsActivity();
                        break;
                    case "donate":
                    case "d":
                        fragment = new NekoDonateActivity();
                        break;
                    case "experimental":
                    case "e":
                        fragment = new NekoExperimentalSettingsActivity();
                        break;
                    case "emoji":
                        fragment = new NekoEmojiSettingsActivity();
                        break;
                    case "general":
                    case "g":
                        fragment = new NekoGeneralSettingsActivity();
                        break;
                    case "reportid":
                        SettingsHelper.copyReportId();
                        return;
                    case "update":
                        LaunchActivity.instance.checkAppUpdate(true, progress);
                        return;
                    default:
                        unknown.run();
                        return;
                }
            }
        }
        callback.accept(fragment);
        var row = uri.getQueryParameter("r");
        if (TextUtils.isEmpty(row)) {
            row = uri.getQueryParameter("row");
        }
        if (!TextUtils.isEmpty(row)) {
            var rowFinal = row;
            AndroidUtilities.runOnUIThread(() -> fragment.scrollToRow(rowFinal, unknown));
        }
    }

    public static void copyReportId() {
        AndroidUtilities.addToClipboard(AnalyticsHelper.userId);
        BulletinFactory.global().createSimpleBulletin(R.raw.copy, LocaleController.getString(R.string.TextCopied), LocaleController.getString(R.string.CopyReportIdDescription)).show();
    }
}
