/*
 * Copyright (C) 2011 The original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.zapta.apps.maniana.widget;

import java.io.File;
import java.util.List;

import javax.annotation.Nullable;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.format.Time;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.MeasureSpec;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.zapta.apps.maniana.R;
import com.zapta.apps.maniana.main.MainActivity;
import com.zapta.apps.maniana.main.ResumeAction;
import com.zapta.apps.maniana.model.AppModel;
import com.zapta.apps.maniana.model.ItemModelReadOnly;
import com.zapta.apps.maniana.preferences.ItemFontVariation;
import com.zapta.apps.maniana.preferences.PreferencesTracker;
import com.zapta.apps.maniana.services.AppServices;
import com.zapta.apps.maniana.util.BitmapUtil;
import com.zapta.apps.maniana.util.ColorUtil;
import com.zapta.apps.maniana.util.DisplayUtil;
import com.zapta.apps.maniana.util.FileUtil;
import com.zapta.apps.maniana.util.LogUtil;
import com.zapta.apps.maniana.util.Orientation;
import com.zapta.apps.maniana.widget.ListWidgetSize.OrientationInfo;

/**
 * Base class for the task list widgets.
 * <p>
 * The code below went though several iterations to make it functional, efficient and to overcome
 * the limitations of RemoteViews (e.g. non support of custom fonts like Vavont). I will try to
 * outline here the overall design as well as non obvious considerations. If you change this code or
 * adapt it to other applications make sure to throughly test it with different Android versions
 * screen sizes and orientations.
 * <p>
 * Main features: 1. Supports custom fonts (not supported directly by Remote Views). 2. Single code
 * and layout supports multiple widget sizes and both orientation. 3. Automatic and smooth
 * orientation change when home launcher changes orientation. 4. Uses efficiently a static bitmap
 * background (paper). 5. Multiple 'hot areas' on the widget that dispatch intents.
 * <p>
 * The main layout of this widget is widget_list_layout.xml. It is used for all supported widget
 * sizes (currently 5 of them) and both orientation. The layout contains these parts 1. A place to
 * set the static background image (paper). Note: this bitmap could be included in the image bitmap
 * (part 2 below) but this increased the size of the dynamic bitmap files and slow the widget
 * update. 2. A place to show two bitmap images, from local file URI, for portrait and landscape
 * views of each of the 5 widget size (total of 2 x 5 images). The visibility of the images in each
 * pair are controlled automatically by a style that enables one in portrait mode and the other in
 * landscape mode. Further, the widget code, when it set a widget RemoteViews for a widget of a
 * certain size, make sure to disable (visibility = GONE) all the images of the other sizes. 3. Hot
 * areas that can be set to trigger intents. These areas overlay the buttons of the widget which are
 * part of the bitmap images (part 2 above).
 * <p>
 * Once the remote views is setup for a given widget instance, the orientation change in the home
 * launcher result in smooth widget orientation change as the widget contain recreate a new widget
 * layout with the current landscape/portrait style and applies to it the commands recorded in the
 * RemoteViews.
 * <p>
 * When a widget of a given size is updated, the update method below creates two bitmap .png files
 * whose name encode the widget size and the orientation. Then the RemoteViews is set such that the
 * respective two ImageViews in the main layout are set with URI to the respective files.
 * <p>
 * The two files are rendered from a template layout that includes the widget toolbar and text. The
 * layout is populated and then rendered into two bitmaps with size for landscape and portrait
 * orientation respectively. These bitmaps are then save to local files, overwriting previous files
 * for this widget size. Note that the template layout is inflated locally and not via a
 * RemoteViews.
 * <p>
 * What did not work? 1. Passing the bitmap to the remote views via setImageViewBitmap(). For large
 * widget the bitmap was too big and once in a while Android just dropped it. 2. Passing the bitmap
 * to the remote views via multiple 'slices' of setImageViewBitmap and ImageView. Same problem as
 * above. 3. Using only a pair of ImageView and setting their size dynamically to the current widget
 * size. Could not find a way to do it with RemoteViews. 4. Letting the dynamic template bitmap file
 * to set the size of the containing ImageView. By default, the ImageView scaled the fetched bitmap
 * file by density(). Pre scaling the bitmap by this factor to compensate for the down scaling works
 * but introduces font artifcats due to the two scaling operations. 5. Using a single paper
 * background resource. When applying a background bitmap resource to a view, Android can stretch it
 * to fit the view size but does not shrink it, instead it stretch the view to match the background
 * image. As a result, background image must be LE the view size. Using a single background bitmap
 * that is smaller than the smaller widget size will result in poor quality when used with larger
 * widgets due to the low resolution. For this reason, we select dynamically one out of 4 or so
 * background image resources of different size. This way we can have the best match. Note that
 * sizes are are measures in actual pixels, not DIP, so a 4x3 widget for example can have different
 * sizes based on each device's density.
 * 
 * @author Tal Dayan
 */
public abstract class ListWidgetProvider extends BaseWidgetProvider {

    /** Used to avoid too frequent file garbage collection. */
    private static long gLastGarbageCollectionTimeMillis = 0;

    public ListWidgetProvider() {
    }

    protected abstract ListWidgetSize listWidgetSize();

    /**
     * Called by the widget host. Updates one or more widgets of the same size.
     */
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        update(context, appWidgetManager, listWidgetSize(), appWidgetIds, loadModelForWidgets(context));
    }

    /**
     * Internal widget update method that accepts the model as a parameter. Updates one or more
     * widgets of the same size.
     * 
     * @param context the widget context
     * @param appWidgetManager appWidgetManager to use.
     * @param listWidgetSize the size of the updated widget instance
     * @param appWidgetIds a list of widget instance ids to update.
     * @param model the Maniana app model instance with the data to render. If null, the widget will
     *        display an error message.
     */
    private static final void update(Context context, AppWidgetManager appWidgetManager,
            ListWidgetSize listWidgetSize, int[] appWidgetIds, @Nullable AppModel model) {

        if (appWidgetIds.length == 0) {
            return;
        }

        final SharedPreferences sharedPreferences = PreferenceManager
                .getDefaultSharedPreferences(context);

        // Create the template view. We will later render it to a bitmap.
        //
        // NOTE: we use a template layout that is rendered to a bitmap rather rendering directly
        // a remote view. This allows us to use custom fonts which are not supported by
        // remote view. This also increase the complexity and makes the widget more sensitive
        // to resizing.
        //
        LayoutInflater layoutInflater = (LayoutInflater) context
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        final LinearLayout template = (LinearLayout) layoutInflater.inflate(
                R.layout.widget_list_template_layout, null);

        // Set template background. This can be the background solid color or the paper color.
        final boolean backgroundPaper = PreferencesTracker
                .readWidgetBackgroundPaperPreference(sharedPreferences);
        final int templateBackgroundColor = templateBackgroundColor(sharedPreferences,
                backgroundPaper);
        final View backgroundColorView = template.findViewById(R.id.widget_list_background_color);
        backgroundColorView.setBackgroundColor(templateBackgroundColor);

        // TODO: cache variation or at least custom typefaces
        final ItemFontVariation fontVariation = ItemFontVariation.newFromWidgetPreferences(context,
                sharedPreferences);

        // Set template view toolbar
        final boolean toolbarEanbled = PreferencesTracker
                .readWidgetShowToolbarPreference(sharedPreferences);
        final boolean showToolbarBackground = (toolbarEanbled && !backgroundPaper);
        final int titleSize = WidgetUtil.titleTextSize(listWidgetSize, fontVariation.getTextSize());
        setTemplateToolbar(context, template, toolbarEanbled, showToolbarBackground, titleSize);

        // Set template view item list
        final LinearLayout itemListView = (LinearLayout) template
                .findViewById(R.id.widget_list_template_item_list);
        populateTemplateItemList(context, itemListView, model, fontVariation, sharedPreferences,
                layoutInflater);

        // Create the widget remote view
        final RemoteViews remoteViews = new RemoteViews(context.getPackageName(),
                R.layout.widget_list_layout);
        setOnClickLaunch(context, remoteViews, R.id.widget_list_bitmaps,
                ResumeAction.ONLY_RESET_PAGE);

        setRemoteViewsToolbar(context, remoteViews, toolbarEanbled);

        renderOneOrientation(context, remoteViews, template, listWidgetSize, Orientation.PORTRAIT,
                backgroundPaper);
        renderOneOrientation(context, remoteViews, template, listWidgetSize, Orientation.LANDSCAPE,
                backgroundPaper);

        // Flush the remote view
        appWidgetManager.updateAppWidget(appWidgetIds, remoteViews);
    }

    /** Compute the template background color. */
    private static int templateBackgroundColor(final SharedPreferences sharedPreferences,
            final boolean backgroundPaper) {
        if (!backgroundPaper) {
            return PreferencesTracker.readWidgetBackgroundColorPreference(sharedPreferences);
        }

        // Using paper background.
        return ColorUtil.mapPaperColorPrefernce(PreferencesTracker
                .readWidgetPaperColorPreference(sharedPreferences));
    }

    /** Set the image of a single orientation. */
    private static final void renderOneOrientation(Context context, RemoteViews remoteViews,
            View template, ListWidgetSize listWidgetSize, Orientation orientation,
            boolean backgroundPaper) {

        // Template view is now fully populated. Render it as a bitmap. First we render it
        // using screen native resolution.
        final float density = DisplayUtil.getDensity(context);

        final OrientationInfo orientationInfo = orientation.isPortrait ? listWidgetSize.portraitInfo
                : listWidgetSize.landscapeInfo;

        final int widthPixels = (int) context.getResources().getDimensionPixelSize(
                orientationInfo.widthDipResourceId);

        final int heightPixels = (int) context.getResources().getDimensionPixelSize(
                orientationInfo.heightDipResourceId);

        final PaperBackground paperBackground = PaperBackground.getBestSize(widthPixels,
                heightPixels);

        final int shadowRightPixels = backgroundPaper ? paperBackground
                .shadowRightPixels(widthPixels) : 0;
        final int shadowBottomPixels = backgroundPaper ? paperBackground
                .shadowBottomPixels(heightPixels) : 0;

        // Set padding to match the drop shadow portion of paper background, if used.
        template.setPadding(0, 0, shadowRightPixels, shadowBottomPixels);

        // NTOE: ARGB_4444 results in a smaller file than ARGB_8888 (e.g. 50K vs 150k)
        // but does not look as good.
        final Bitmap bitmap1 = Bitmap.createBitmap(widthPixels, heightPixels,
                Bitmap.Config.ARGB_8888);

        final Canvas canvas = new Canvas(bitmap1);

        template.measure(MeasureSpec.makeMeasureSpec(widthPixels, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightPixels, MeasureSpec.EXACTLY));
        // TODO: subtract '1' from ends?
        template.layout(0, 0, widthPixels, heightPixels);

        template.draw(canvas);

        // NOTE: rounding the bitmap here when paper background is selected will do nothing
        // since the paper background is added later via the remote views.
        final Bitmap bitmap2;
        if (backgroundPaper) {
            bitmap2 = bitmap1;
        } else {
            bitmap2 = BitmapUtil.roundCornersRGB888(bitmap1, (int) (4 * density + 0.5f));
        }

        // NOTE: RemoteViews class has an issue with transferring large bitmaps. As a workaround, we
        // transfer the bitmap using a file URI. We could transfer small widgets directly
        // as bitmap but use file based transfer for all sizes for the sake of simplicity.
        // For more information on this issue see http://tinyurl.com/75jh2yf

        final String fileName = orientationInfo.imageFileName;

        // We make the file world readable so the home launcher can pull it via the file URI.
        // TODO: if there are security concerns about having this file readable, append to it
        // a long random suffix and cleanup the old ones.
        FileUtil.writeBitmapToPngFile(context, bitmap2, fileName, true);

        final Uri uri = Uri.fromFile(new File(context.getFilesDir(), fileName));

        // Set the bitmap images of given orientation. The bitmap of the size we currently
        // process is set and the other are made GONE. Only bitmaps of the given orientation
        // are touched here
        for (ListWidgetSize iterListWidgetSize : ListWidgetSize.LIST_WIDGET_SIZES) {
            final boolean thisSize = (iterListWidgetSize == listWidgetSize);
            final OrientationInfo iterOrientationInfo = orientation.isPortrait ? iterListWidgetSize.portraitInfo
                    : iterListWidgetSize.landscapeInfo;
            final int iterBitmapResource = iterOrientationInfo.imageViewId;
            if (thisSize) {
                // NOTE: setting up a temporary dummy image to cause the image view to reload the
                // file URI in case it changed.
                remoteViews.setInt(iterBitmapResource, "setImageResource", R.drawable.place_holder);
                remoteViews.setUri(iterBitmapResource, "setImageURI", uri);
                // Set paper background if used or transparent otherwise.
                // TODO: will using the background solid color here rather than the template bitmap
                // reduce the file size? If so, make this change.
                if (backgroundPaper) {
                    remoteViews.setInt(iterBitmapResource, "setBackgroundResource",
                            paperBackground.drawableResourceId);
                } else {
                    remoteViews.setInt(iterBitmapResource, "setBackgroundColor", 0x00000000);
                }
            } else {
                // A different size. Disable it, regardless of orientation.
                remoteViews.setInt(iterBitmapResource, "setVisibility", View.GONE);
            }
        }
    }

    /**
     * Populate the given template layout with task data and/or informative messages.
     * 
     * @param context the app context.
     * @param itemListView the inflated template layout to fill.
     * @param model the app data. If null, will be populated with a error message.
     * @param fontVariation widget font variation to use.
     * @param sharedPreferences shared preference to fetch widget settings.
     * @param layoutInflater an inflater to use to inflate layouts of individual tasks.
     */
    private static final void populateTemplateItemList(Context context, LinearLayout itemListView,
            @Nullable AppModel model, ItemFontVariation fontVariation,
            SharedPreferences sharedPreferences, LayoutInflater layoutInflater) {
        // For debugging
        final boolean debugTimestamp = false;
        if (debugTimestamp) {
            final String message = String.format("[%s]", SystemClock.elapsedRealtime() / 1000);
            addTemplateMessageItem(context, itemListView, message, fontVariation, layoutInflater);
        }

        if (model == null) {
            addTemplateMessageItem(context, itemListView, "(Maniana data not found)",
                    fontVariation, layoutInflater);
            return;
        }

        // Read preference
        final boolean includeCompletedItems = PreferencesTracker
                .readWidgetShowCompletedItemsPreference(sharedPreferences);

        // TODO: reorganize the code. No need to read lock preference if date now is same as the
        // model
        Time now = new Time();
        now.setToNow();

        final List<ItemModelReadOnly> items = WidgetUtil.selectTodaysItems(model, includeCompletedItems);

        if (items.isEmpty()) {
            final String emptyMessage = includeCompletedItems ? "(no tasks)" : "(no active tasks)";
            addTemplateMessageItem(context, itemListView, emptyMessage, fontVariation,
                    layoutInflater);
            return;
        }

        final boolean singleLine = PreferencesTracker
                .readWidgetSingleLinePreference(sharedPreferences);

        for (ItemModelReadOnly item : items) {
            final LinearLayout itemView = (LinearLayout) layoutInflater.inflate(
                    R.layout.widget_list_template_item_layout, null);
            final TextView textView = (TextView) itemView.findViewById(R.id.widget_item_text_view);
            final View colorView = itemView.findViewById(R.id.widget_item_color);

            // NOTE: TextView has a bug that does not allows more than
            // two lines when using ellipsize. Otherwise we would give the user more
            // choices about the max number of lines. More details here:
            // http://code.google.com/p/android/issues/detail?id=2254
            if (!singleLine) {
                textView.setSingleLine(false);
                // NOTE: on ICS (API 14) the text view behaves
                // differently and does not limit the lines to two when ellipsize. For
                // consistency, we limit it explicitly to two lines.
                //
                // TODO: file an Android bug about the different ICS behavior.
                //
                textView.setMaxLines(2);
            }

            textView.setText(item.getText());
            fontVariation.apply(textView, item.isCompleted(), true);

            // If color is NONE show a gray solid color to help visually
            // grouping item text lines.
            final int itemColor = item.getColor().isNone() ? 0xff808080 : item.getColor()
                    .getColor();
            colorView.setBackgroundColor(itemColor);
            itemListView.addView(itemView);
        }
    }

    /**
     * Add an informative message to the item list. These messages are formatted differently than
     * actual tasks.
     */
    private static final void addTemplateMessageItem(Context context, LinearLayout itemListView,
            String message, ItemFontVariation fontVariation, LayoutInflater layoutInflater) {

        final LinearLayout itemView = (LinearLayout) layoutInflater.inflate(
                R.layout.widget_list_template_item_layout, null);
        final TextView textView = (TextView) itemView.findViewById(R.id.widget_item_text_view);
        final View colorView = itemView.findViewById(R.id.widget_item_color);

        // TODO: setup message text using widget font size preference?
        textView.setSingleLine(false);
        textView.setText(message);
        fontVariation.apply(textView, false, true);
        colorView.setVisibility(View.GONE);

        itemListView.addView(itemView);
    }

    /**
     * Set the toolbar portion of the template view.
     * 
     * showToolbarBackground and titleSize are ignored if not toolbarEnabled. titleSize.
     */
    private static final void setTemplateToolbar(Context context, View template,
            boolean toolbarEnabled, boolean showToolbarBackground, int titleSize) {
        final View templateToolbarView = template.findViewById(R.id.widget_list_template_toolbar);
        final TextView templateToolbarTitleView = (TextView) templateToolbarView
                .findViewById(R.id.widget_list_template_toolbar_title);
        final View templateAddTextByVoiceButton = templateToolbarView
                .findViewById(R.id.widget_list_template_toolbar_add_by_voice);

        if (!toolbarEnabled) {
            templateToolbarView.setVisibility(View.GONE);
            return;
        }

        // Make toolbar visible
        templateToolbarView.setVisibility(View.VISIBLE);
        templateToolbarTitleView.setTextSize(titleSize);

        // Show or hide toolbar background.
        if (showToolbarBackground) {
            templateToolbarView.setBackgroundResource(R.drawable.widget_toolbar_background);
        } else {
            templateToolbarView.setBackgroundColor(0x00000000);
        }

        // The voice recognition button is shown only if this device supports voice recognition.
        if (AppServices.isVoiceRecognitionSupported(context)) {
            templateAddTextByVoiceButton.setVisibility(View.VISIBLE);
        } else {
            templateAddTextByVoiceButton.setVisibility(View.GONE);
        }
    }

    /** Set/disable the toolbar click overlay in the remote views layout. */
    private static final void setRemoteViewsToolbar(Context context, RemoteViews remoteViews,
            boolean toolbarEnabled) {
        // Set or disable the click overlay of the add-item-by-text button.
        if (toolbarEnabled) {
            remoteViews.setInt(R.id.widget_list_toolbar_add_by_text_overlay, "setVisibility",
                    View.VISIBLE);
            setOnClickLaunch(context, remoteViews, R.id.widget_list_toolbar_add_by_text_overlay,
                    ResumeAction.ADD_NEW_ITEM_BY_TEXT);
        } else { // templateAddTextByVoiceButton.setVisibility(View.GONE);
            remoteViews.setInt(R.id.widget_list_toolbar_add_by_text_overlay, "setVisibility",
                    View.GONE);
        }

        // Set or disable the click overlay of the add-item-by-voice button.
        if (toolbarEnabled && AppServices.isVoiceRecognitionSupported(context)) {
            remoteViews.setInt(R.id.widget_list_toolbar_add_by_voice_overlay, "setVisibility",
                    View.VISIBLE);
            setOnClickLaunch(context, remoteViews, R.id.widget_list_toolbar_add_by_voice_overlay,
                    ResumeAction.ADD_NEW_ITEM_BY_VOICE);
        } else {
            remoteViews.setInt(R.id.widget_list_toolbar_add_by_voice_overlay, "setVisibility",
                    View.GONE);
        }
    }

    /** Set onClick() action of given remote view element to launch the app. */
    private static final void setOnClickLaunch(Context context, RemoteViews remoteViews,
            int viewId, ResumeAction resumeAction) {
        final Intent intent = new Intent(context, MainActivity.class);
        ResumeAction.setInIntent(intent, resumeAction);
        // Setting unique intent action and using FLAG_UPDATE_CURRENT to avoid cross
        // reuse of pending intents. See http://tinyurl.com/8axhrlp for more info.
        intent.setAction("maniana.list_widget." + resumeAction.toString());
        final PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        remoteViews.setOnClickPendingIntent(viewId, pendingIntent);
    }

    /**
     * Update all list widgets using a given model.
     * 
     * This method is called from the main activity when model changes need to be flushed to the
     * widgets. The model is already pushed and sorted according to the currnet setting.
     * 
     * @param context app context.
     * @param model app model with task data. If null, widgets will show a warning message.
     */
    public static void updateAllListWidgetsFromModel(Context context, @Nullable AppModel model) {
        final AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);

        for (ListWidgetSize listWidgetSize : ListWidgetSize.LIST_WIDGET_SIZES) {
            final int widgetIds[] = appWidgetManager.getAppWidgetIds(new ComponentName(context,
                    listWidgetSize.widgetProviderClass));
            // Update all widgets of this size, if any.
            if (widgetIds != null) {
                update(context, appWidgetManager, listWidgetSize, widgetIds, model);
            }
        }

        // Since we updated above all active widget files, it is safe to delete old ones.
        garbageCollectOlderFiles(context);
    }

    /**
     * Garbage collect old widget image files. This should be called only after all active widget
     * files as been updated to make sure it does not deleted active widget files.
     */
    private static void garbageCollectOlderFiles(Context context) {
        final long timeNowMillis = System.currentTimeMillis();

        // If we performed a garbage collection in the last hour, do nothing. We don't
        // want to incure garbage collection delay on each update. If the app got destroyed
        // and recreated, no big deal, we will just do one more garbage collection.
        final long dtMillis = (timeNowMillis - gLastGarbageCollectionTimeMillis);
        if (dtMillis >= 0 && dtMillis <= 60 * 60 * 1000) {
            return;
        }
        // We don't bother to protect with a lock.
        gLastGarbageCollectionTimeMillis = timeNowMillis;

        // Track stats
        int deletedFileCount = 0;
        int nonRelatedFiles = 0;
        int keptFileCount = 0;

        final File dir = context.getFilesDir();
        final String fileNames[] = dir.list();
        for (String fileName : fileNames) {
            // TODO: share file name const with ListWidgetSize
            if (!fileName.startsWith("list_widget_image_")) {
                nonRelatedFiles++;
                continue;
            }

            final File file = new File(dir, fileName);
            final long lastModifiedMillis = file.lastModified();
            final long fileAgeMillis = timeNowMillis - lastModifiedMillis;
            final long fileAgeMinutes = fileAgeMillis / (1000 * 60);
            // We use an arbitrary age threshold of 10 minutes. Since the active widget files
            // were just been updated, we could use a much shorter threshold. We are also 
            // deleting files that are too much in the future, in case a file happen to 
            // have time far in the future.
            if (Math.abs(fileAgeMinutes) > 10){
                final boolean deletedOk = file.delete();
                if (deletedOk) {
                    LogUtil.info("Garbage collected %s, %d minutes old", fileName, fileAgeMinutes);
                    deletedFileCount++;
                } else {
                    LogUtil.error("Failed to delete: %s", file.getAbsoluteFile());
                }
            } else {
                keptFileCount++;
            }
        }
        LogUtil.debug("Garbage collected %d widget files in %dms, kept %d + %d files.",
                deletedFileCount, System.currentTimeMillis() - timeNowMillis, keptFileCount,
                nonRelatedFiles);
    }
}
