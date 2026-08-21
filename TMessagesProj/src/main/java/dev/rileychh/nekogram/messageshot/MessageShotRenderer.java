/*
 * Message Shot is inspired by AyuGram Desktop's Message Shot:
 * https://github.com/AyuGram/AyuGramDesktop — original author @Radolyn (2026).
 * This Android implementation is native to Miekogram.
 */
package dev.rileychh.nekogram.messageshot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.ViewGroup;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ChatMessageSharedResources;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Cells.ChatMessageCell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class MessageShotRenderer extends ViewGroup {
    private static final int CONTENT_WIDTH_DP = 360;
    private static final int RIGHT_OVERFLOW_DP = 128;
    public static final int WIDTH_DP = CONTENT_WIDTH_DP + RIGHT_OVERFLOW_DP;
    private static final int MAX_EDGE = 4096;
    private static final int MAX_PIXELS = 16_777_216;

    private final int account;
    private final ChatActivity chatActivity;
    private final ArrayList<MessageObject> messages;
    private final ArrayList<ChatMessageCell> cells = new ArrayList<>();
    private final Set<String> pendingMedia = new HashSet<>();
    private final HashMap<Long, MessageObject.GroupedMessages> groups = new HashMap<>();
    private final Theme.ResourcesProvider resourcesProvider;
    private ChatMessageSharedResources sharedResources;
    private boolean bound;
    private int naturalWidth;

    public MessageShotRenderer(Context context, ChatActivity chatActivity, ArrayList<MessageObject> selected) {
        super(context);
        this.chatActivity = chatActivity;
        this.account = chatActivity.getCurrentAccount();
        this.messages = new ArrayList<>(selected);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        naturalWidth = AndroidUtilities.dp(CONTENT_WIDTH_DP);
        resourcesProvider = new DefaultLightResourcesProvider();
        sharedResources = new ChatMessageSharedResources(context);
        prepareGroups();
        for (MessageObject message : messages) {
            ChatMessageCell cell = new ChatMessageCell(context, account, true, sharedResources, resourcesProvider);
            cell.setMessageShotForceIncoming(true);
            cell.setMessageShotOmitTime(true);
            cell.setDelegate(createDelegate());
            cells.add(cell);
            addView(cell, new LayoutParams(naturalWidth, LayoutParams.WRAP_CONTENT));
        }
    }

    private void prepareGroups() {
        Collections.sort(messages, Comparator.comparingInt((MessageObject message) -> message.messageOwner.date).thenComparingInt(MessageObject::getId));
        groups.clear();
        for (MessageObject message : messages) {
            long groupId = message.getGroupId();
            if (groupId == 0) {
                continue;
            }
            MessageObject.GroupedMessages group = groups.get(groupId);
            if (group == null) {
                group = new MessageObject.GroupedMessages();
                group.groupId = groupId;
                groups.put(groupId, group);
            }
            group.messages.add(message);
        }
        for (MessageObject.GroupedMessages group : groups.values()) {
            group.calculate();
        }
    }

    private ChatMessageCell.ChatMessageCellDelegate createDelegate() {
        return new ChatMessageCell.ChatMessageCellDelegate() {
            @Override
            public boolean isAdmin(long uid) {
                TLRPC.Chat chat = chatActivity.getCurrentChat();
                return chat != null && !ChatObject.isChannelAndNotMegaGroup(chat) && MessagesController.getInstance(account).isAdmin(chat.id, uid);
            }

            @Override
            public boolean isOwner(long uid) {
                TLRPC.Chat chat = chatActivity.getCurrentChat();
                return chat != null && !ChatObject.isChannelAndNotMegaGroup(chat) && MessagesController.getInstance(account).isOwner(chat.id, uid);
            }

            @Override
            public String getAdminRank(long uid) {
                TLRPC.Chat chat = chatActivity.getCurrentChat();
                return chat == null || ChatObject.isChannelAndNotMegaGroup(chat) ? null : MessagesController.getInstance(account).getAdminRank(chat.id, uid);
            }

            @Override
            public boolean canPerformActions() {
                return false;
            }
        };
    }

    private MessageObject.GroupedMessages groupFor(MessageObject message) {
        MessageObject.GroupedMessages group = groups.get(message.getGroupId());
        return group != null && group.messages.size() > 1 ? group : null;
    }

    private void configureCell(ChatMessageCell cell) {
        TLRPC.Chat chat = chatActivity.getCurrentChat();
        cell.isChat = true;
        cell.isBotForum = false;
        cell.isSavedChat = false;
        cell.isSavedPreviewChat = false;
        cell.isBot = false;
        cell.isMegagroup = ChatObject.isChannel(chat) && chat.megagroup;
        cell.isForum = ChatObject.isForum(chat);
        cell.isMonoForum = ChatObject.isMonoForum(chat);
        cell.isForumGeneral = false;
        cell.isThreadChat = false;
        cell.hasDiscussion = false;
        cell.isPinned = false;
        cell.isReportChat = false;
        cell.linkedChatId = 0;
        cell.isRepliesChat = false;
        cell.isPinnedChat = false;
        cell.isAllChats = false;
        cell.isSideMenued = false;
        cell.isSideMenuEnabled = false;
        cell.sideMenuAlpha = 0f;
        cell.sideMenuWidth = 0;
        cell.setShowTopic(true);
    }

    private boolean canShareBubble(MessageObject previous, MessageObject current) {
        if (previous == null || current == null || previous.messageOwner == null || current.messageOwner == null) {
            return false;
        }
        if (previous.messageOwner.reply_markup instanceof TLRPC.TL_replyInlineMarkup || current.messageOwner.paid_message_stars > 0) {
            return false;
        }
        if (Math.abs(previous.messageOwner.date - current.messageOwner.date) > 300) {
            return false;
        }
        if (previous.isImportedForward() || current.isImportedForward()) {
            if (!previous.isImportedForward() || !current.isImportedForward() || previous.messageOwner.fwd_from == null || current.messageOwner.fwd_from == null) {
                return false;
            }
            if (Math.abs(previous.messageOwner.fwd_from.date - current.messageOwner.fwd_from.date) > 300) {
                return false;
            }
            if (previous.messageOwner.fwd_from.from_name != null && current.messageOwner.fwd_from.from_name != null) {
                return previous.messageOwner.fwd_from.from_name.equals(current.messageOwner.fwd_from.from_name);
            }
            if (previous.messageOwner.fwd_from.from_id != null && current.messageOwner.fwd_from.from_id != null) {
                return MessageObject.getPeerId(previous.messageOwner.fwd_from.from_id) == MessageObject.getPeerId(current.messageOwner.fwd_from.from_id);
            }
            return false;
        }
        if (previous.getFromChatId() != current.getFromChatId()) {
            return false;
        }
        TLRPC.Chat chat = chatActivity.getCurrentChat();
        return chat == null || !chat.megagroup || previous.getFromChatId() >= 0;
    }

    private boolean isPinnedTop(int index) {
        return index > 0 && canShareBubble(messages.get(index - 1), messages.get(index));
    }

    private boolean isPinnedBottom(int index) {
        return index + 1 < messages.size() && canShareBubble(messages.get(index), messages.get(index + 1));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        bindCells();
    }

    private void bindCells() {
        if (bound || !isAttachedToWindow()) {
            return;
        }
        bound = true;
        for (int i = 0; i < cells.size(); i++) {
            MessageObject message = messages.get(i);
            ChatMessageCell cell = cells.get(i);
            configureCell(cell);
            cell.setResourcesProvider(resourcesProvider);
            cell.setMessageShotForceIncoming(true);
            cell.setMessageShotOmitTime(true);
            cell.setMessageObject(message, groupFor(message), isPinnedBottom(i), isPinnedTop(i), i == 0, i == messages.size() - 1);
            requestMedia(cell, message);
        }
        requestLayout();
        invalidate();
    }

    private void requestMedia(ChatMessageCell cell, MessageObject message) {
        ImageReceiver receiver = cell.getPhotoImage();
        requestMediaLocation(receiver != null ? receiver.getImageLocation() : null, message);
        requestMediaLocation(receiver != null ? receiver.getThumbLocation() : null, message);
        ImageReceiver avatar = cell.getAvatarImage();
        requestMediaLocation(avatar != null ? avatar.getImageLocation() : null, message);
        requestMediaLocation(avatar != null ? avatar.getThumbLocation() : null, message);
    }

    private void requestMediaLocation(ImageLocation location, MessageObject message) {
        if (location == null) {
            return;
        }
        String key = location.getKey(null, null, false);
        if (key == null || key.isEmpty()) {
            return;
        }
        pendingMedia.add(key);
        FileLoader.getInstance(account).loadFile(location, message, null, FileLoader.PRIORITY_NORMAL, 0);
    }

    public boolean hasPendingMedia() {
        return !pendingMedia.isEmpty();
    }

    public void fileLoaded(String key) {
        if (key != null) {
            pendingMedia.remove(key);
        }
        invalidate();
    }

    public void fileLoadFailed(String key) {
        if (key != null) {
            pendingMedia.remove(key);
        }
        invalidate();
    }


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = 0;
        for (ChatMessageCell cell : cells) {
            cell.measure(MeasureSpec.makeMeasureSpec(naturalWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            height += cell.getMeasuredHeight();
        }
        setMeasuredDimension(AndroidUtilities.dp(WIDTH_DP), height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int y = 0;
        for (ChatMessageCell cell : cells) {
            cell.setParentViewSize(naturalWidth, getMeasuredHeight());
            cell.layout(0, y, naturalWidth, y + cell.getMeasuredHeight());
            y += cell.getMeasuredHeight();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        HashSet<MessageObject.GroupedMessages> drawn = new HashSet<>();
        for (ChatMessageCell cell : cells) {
            MessageObject.GroupedMessages group = cell.getCurrentMessagesGroup();
            if (group != null && !drawn.add(group)) {
                continue;
            }
            if (group != null) {
                int first = cells.indexOf(findCell(group.messages.get(0)));
                int last = first + group.messages.size() - 1;
                if (first >= 0 && last < cells.size()) {
                    ChatMessageCell anchor = cells.get(first);
                    int l = 0;
                    int t = cells.get(first).getTop();
                    int r = naturalWidth;
                    int b = cells.get(last).getBottom();
                    anchor.drawBackground(canvas, l, t, r, b, true, true, false, 0);
                }
        }
    }
    }

    private ChatMessageCell findCell(MessageObject message) {
        for (ChatMessageCell cell : cells) {
            if (cell.getMessageObject() == message || cell.getMessageObject() != null && cell.getMessageObject().getId() == message.getId()) {
                return cell;
            }
        }
        return null;
    }


    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);

        for (ChatMessageCell cell : cells) {
            drawGroupAvatar(canvas, cell);
        }
        for (ChatMessageCell cell : cells) {
            MessageObject.GroupedMessagePosition position = cell.getCurrentPosition();
            if (position == null) {
                continue;
            }
            canvas.save();
            canvas.translate(cell.getLeft(), cell.getTop());
            if ((position.flags & cell.captionFlag()) != 0) {
                cell.drawCaptionLayout(canvas, false, cell.getAlpha());
            }
            if ((position.flags & MessageObject.POSITION_FLAG_BOTTOM) != 0 && (position.flags & MessageObject.POSITION_FLAG_LEFT) != 0) {
                cell.drawReactionsLayout(canvas, cell.getAlpha(), null);
                cell.drawCommentLayout(canvas, cell.getAlpha());
            }
            if (position.minX == 0 && position.minY == 0 && cell.hasNameLayout()) {
                cell.drawNamesLayout(canvas, cell.getAlpha());
            }
            canvas.restore();
        }
    }

    private void drawGroupAvatar(Canvas canvas, ChatMessageCell cell) {
        if (cell.drawPinnedBottom()) {
            return;
        }
        ImageReceiver avatar = cell.getAvatarImage();
        if (avatar == null) {
            return;
        }
        avatar.setImageY(cell.getBottom() - AndroidUtilities.dp(44));
        avatar.setAlpha(cell.getAlpha());
        avatar.setVisible(true, false);
        avatar.draw(canvas);
    }

    public Bitmap captureRawBitmap() {
        if (LooperHolder.isNotMainThread()) {
            throw new IllegalStateException("Message Shot capture must run on the UI thread");
        }
        int width = Math.max(1, getWidth());
        int height = Math.max(1, getHeight());
        Bitmap bitmap = drawBitmap(width, height);
        int requiredWidth = width;
        for (ChatMessageCell cell : cells) {
            requiredWidth = Math.max(requiredWidth, cell.getLeft() + cell.getBoundsRight() + AndroidUtilities.dp(8));
        }
        if (requiredWidth > width) {
            bitmap.recycle();
            bitmap = drawBitmap(requiredWidth, height);
        }
        return bitmap;
    }

    private Bitmap drawBitmap(int width, int height) {
        float scale = Math.min(1f, MAX_EDGE / (float) Math.max(width, height));
        scale = Math.min(scale, (float) Math.sqrt(MAX_PIXELS / (double) (width * (long) height)));
        int bitmapWidth = Math.max(1, Math.round(width * scale));
        int bitmapHeight = Math.max(1, Math.round(height * scale));
        Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(scale, scale);
        draw(canvas);
        return bitmap;
    }

    public static Bitmap finishBitmap(Bitmap raw) {
        if (raw == null || raw.isRecycled()) {
            return null;
        }
        int width = raw.getWidth();
        int height = raw.getHeight();
        int[] pixels = new int[width];
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            raw.getPixels(pixels, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                if (Color.alpha(pixels[x]) != 0) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = y;
                }
            }
        }
        if (right < left || bottom < top) {
            return null;
        }
        int padding = Math.max(1, Math.round(AndroidUtilities.dp(16)));
        int outWidth = right - left + 1 + padding * 2;
        int outHeight = bottom - top + 1 + padding * 2;
        Bitmap result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawBitmap(raw, new Rect(left, top, right + 1, bottom + 1), new Rect(padding, padding, padding + right - left + 1, padding + bottom - top + 1), null);
        return result;
    }

    private static final class DefaultLightResourcesProvider implements Theme.ResourcesProvider {
        private final HashMap<String, Drawable> drawables = new HashMap<>();

        @Override
        public int getColor(int key) {
            return Theme.getDefaultColor(key);
        }

        @Override
        public Drawable getDrawable(String key) {
            Drawable drawable = drawables.get(key);
            if (drawable != null) {
                return drawable;
            }
            switch (key) {
                case Theme.key_drawable_msgIn:
                    drawable = new Theme.MessageDrawable(Theme.MessageDrawable.TYPE_TEXT, false, false, this);
                    break;
                case Theme.key_drawable_msgInSelected:
                    drawable = new Theme.MessageDrawable(Theme.MessageDrawable.TYPE_TEXT, false, true, this);
                    break;
                case Theme.key_drawable_msgInMedia:
                    drawable = new Theme.MessageDrawable(Theme.MessageDrawable.TYPE_MEDIA, false, false, this);
                    break;
                case Theme.key_drawable_msgInMediaSelected:
                    drawable = new Theme.MessageDrawable(Theme.MessageDrawable.TYPE_MEDIA, false, true, this);
                    break;
                case Theme.key_drawable_msgOut:
                    drawable = new Theme.MessageDrawable(Theme.MessageDrawable.TYPE_TEXT, true, false, this);
                    break;
                case Theme.key_drawable_msgOutSelected:
                    drawable = new Theme.MessageDrawable(Theme.MessageDrawable.TYPE_TEXT, true, true, this);
                    break;
                case Theme.key_drawable_msgOutMedia:
                    drawable = new Theme.MessageDrawable(Theme.MessageDrawable.TYPE_MEDIA, true, false, this);
                    break;
                case Theme.key_drawable_msgOutMediaSelected:
                    drawable = new Theme.MessageDrawable(Theme.MessageDrawable.TYPE_MEDIA, true, true, this);
                    break;
                default:
                    return null;
            }
            drawables.put(key, drawable);
            return drawable;
        }

        @Override
        public boolean isDark() {
            return false;
        }
    }

    private static final class LooperHolder {
        private static boolean isNotMainThread() {
            return android.os.Looper.myLooper() != android.os.Looper.getMainLooper();
        }
    }

}
