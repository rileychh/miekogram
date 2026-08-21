/*
 * Message Shot is inspired by AyuGram Desktop's Message Shot:
 * https://github.com/AyuGram/AyuGramDesktop — original author @Radolyn (2026).
 * This Android implementation is native to Miekogram.
 */
package dev.rileychh.nekogram.messageshot;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ContentPreviewViewer;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.WeakHashMap;

public final class MessageShotGenerator implements NotificationCenter.NotificationCenterDelegate {
    private static final WeakHashMap<ChatActivity, MessageShotGenerator> ACTIVE = new WeakHashMap<>();

    private final ChatActivity chatActivity;
    private final int account;
    private final Runnable onShotCreated;
    private final MessageShotRenderer renderer;

    private ViewGroup rendererParent;
    private Runnable timeoutRunnable;
    private File generatedFile;
    private boolean editorOwnsFile;
    private boolean generationStarted;
    private boolean finished;

    private MessageShotGenerator(ChatActivity chatActivity, ArrayList<MessageObject> messages, Runnable onShotCreated) {
        this.chatActivity = chatActivity;
        this.account = chatActivity.getCurrentAccount();
        this.onShotCreated = onShotCreated;
        this.renderer = new MessageShotRenderer(chatActivity.getContext(), chatActivity, messages);
    }

    public static void create(ChatActivity chatActivity, ArrayList<MessageObject> selected, Runnable onShotCreated) {
        ArrayList<MessageObject> messages = new ArrayList<>();
        if (selected != null) {
            for (MessageObject message : selected) {
                if (message != null && !message.deleted) {
                    messages.add(message);
                }
            }
        }
        if (messages.isEmpty() || ACTIVE.containsKey(chatActivity)) {
            return;
        }
        for (MessageObject message : messages) {
            if (!isMessageEligible(message)) {
                return;
            }
        }
        MessageShotGenerator generator = new MessageShotGenerator(chatActivity, messages, onShotCreated);
        ACTIVE.put(chatActivity, generator);
        generator.start();
    }

    public static boolean isMessageEligible(MessageObject message) {
        if (message == null || message.deleted || message.messageOwner == null || message.messageOwner.noforwards || message.isEphemeral() || message.isVoiceOnce() || message.isRoundOnce() || message.isSecretMedia() || message.isSensitive()) {
            return false;
        }
        TLRPC.MessageMedia media = message.messageOwner.media;
        return media == null || media.ttl_seconds == 0;
    }

    private void start() {
        Activity activity = chatActivity.getParentActivity();
        if (activity == null || activity.isFinishing()) {
            finishFailure(null, null);
            return;
        }
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            finishFailure(null, null);
            return;
        }

        rendererParent = (ViewGroup) decor;
        int width = AndroidUtilities.dp(MessageShotRenderer.WIDTH_DP);
        renderer.setTranslationX(-width * 2f);
        renderer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        rendererParent.addView(renderer, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        measureRenderer();

        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoadFailed);
        timeoutRunnable = this::finishGeneration;
        AndroidUtilities.runOnUIThread(timeoutRunnable, 3000);
        waitForMedia();
    }

    private void measureRenderer() {
        int width = AndroidUtilities.dp(MessageShotRenderer.WIDTH_DP);
        renderer.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        renderer.layout(0, 0, width, renderer.getMeasuredHeight());
    }

    private void waitForMedia() {
        if (finished || generationStarted) {
            return;
        }
        if (renderer.hasPendingMedia()) {
            AndroidUtilities.runOnUIThread(this::waitForMedia, 50);
        } else {
            finishGeneration();
        }
    }

    private void finishGeneration() {
        if (finished || generationStarted) {
            return;
        }
        generationStarted = true;
        if (timeoutRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(timeoutRunnable);
            timeoutRunnable = null;
        }

        Bitmap raw = null;
        try {
            measureRenderer();
            raw = renderer.captureRawBitmap();
        } catch (Throwable error) {
            finishFailure(raw, null);
            return;
        }
        detachRenderer();

        Bitmap captured = raw;
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap result = null;
            try {
                result = MessageShotRenderer.finishBitmap(captured);
                if (result == null) {
                    throw new IllegalStateException("empty message shot");
                }
                File directory = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
                File file = new File(directory, "message-shot-" + System.nanoTime() + ".png");
                try (FileOutputStream stream = new FileOutputStream(file)) {
                    if (!result.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw new IllegalStateException("png compression failed");
                    }
                }
                Bitmap finalResult = result;
                AndroidUtilities.runOnUIThread(() -> openStickerEditor(file, captured, finalResult));
            } catch (Throwable error) {
                recycle(result);
                AndroidUtilities.runOnUIThread(() -> finishFailure(captured, null));
            }
        });
    }

    private void openStickerEditor(File file, Bitmap raw, Bitmap result) {
        if (finished) {
            recycle(raw, result);
            file.delete();
            return;
        }
        generatedFile = file;
        ArrayList<Object> photos = new ArrayList<>();
        MediaController.PhotoEntry entry = new MediaController.PhotoEntry(0, 0, 0, file.getAbsolutePath(), 0, false, 0, 0, 0);
        photos.add(entry);
        PhotoViewer.getInstance().setParentActivity(chatActivity, chatActivity.getResourceProvider());
        PhotoViewer.EmptyPhotoViewerProvider provider = new PhotoViewer.EmptyPhotoViewerProvider() {
            @Override
            public boolean allowCaption() {
                return false;
            }

            @Override
            public void sendButtonPressed(int index, VideoEditedInfo videoEditedInfo, boolean notify, int scheduleDate, int scheduleRepeatPeriod, boolean forceDocument) {
                PhotoViewer viewer = PhotoViewer.getInstance();
                viewer.closePhotoAfterSelect = false;
                viewer.doneButtonPressed = false;
                AlertsCreator.ensurePaidMessageConfirmation(account, chatActivity.getDialogId(), 1, payStars -> {
                    chatActivity.sendMedia(entry, videoEditedInfo, notify, scheduleDate, scheduleRepeatPeriod, forceDocument, payStars);
                    viewer.closePhoto(viewer.closePhotoAfterSelectWithAnimation, false);
                    viewer.doneButtonPressed = true;
                });
            }
        };
        boolean opened = PhotoViewer.getInstance().openPhotoForSelect(photos, 0, PhotoViewer.SELECT_TYPE_STICKER, false, provider, chatActivity);
        if (!opened) {
            finishFailure(raw, result);
            return;
        }

        editorOwnsFile = true;
        PhotoViewer.getInstance().enableStickerMode(null, null, false, null);
        PhotoViewer.getInstance().prepareSegmentImage();
        ContentPreviewViewer.getInstance().setStickerSetForCustomSticker(null);
        recycle(raw, result);
        finish(true);
    }

    private void finishFailure(Bitmap raw, Bitmap result) {
        recycle(raw, result);
        finish(false);
        BulletinFactory.of(chatActivity).createErrorBulletin(LocaleController.getString(R.string.MessageShotCreateFailed)).show();
    }

    private void finish(boolean succeeded) {
        if (finished) {
            return;
        }
        finished = true;
        if (timeoutRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(timeoutRunnable);
            timeoutRunnable = null;
        }
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadFailed);
        detachRenderer();
        if (generatedFile != null && !editorOwnsFile) {
            generatedFile.delete();
            generatedFile = null;
        }
        if (ACTIVE.get(chatActivity) == this) {
            ACTIVE.remove(chatActivity);
        }
        if (succeeded && onShotCreated != null) {
            onShotCreated.run();
        }
    }

    private void detachRenderer() {
        if (rendererParent != null) {
            rendererParent.removeView(renderer);
            rendererParent = null;
        }
    }

    private static void recycle(Bitmap... bitmaps) {
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (finished || args.length == 0 || !(args[0] instanceof String)) {
            return;
        }
        String key = (String) args[0];
        if (id == NotificationCenter.fileLoaded) {
            renderer.fileLoaded(key);
        } else if (id == NotificationCenter.fileLoadFailed) {
            renderer.fileLoadFailed(key);
        }
    }
}
