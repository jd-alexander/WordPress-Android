package org.wordpress.android.ui.media;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageLoader;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.fluxc.model.MediaModel;
import org.wordpress.android.fluxc.store.MediaStore;
import org.wordpress.android.fluxc.tools.FluxCImageLoader;
import org.wordpress.android.util.AniUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.DateTimeUtils;
import org.wordpress.android.util.DisplayUtils;
import org.wordpress.android.util.ImageUtils;
import org.wordpress.android.util.MediaUtils;
import org.wordpress.android.util.PhotonUtils;
import org.wordpress.android.util.ToastUtils;

import java.text.SimpleDateFormat;
import java.util.Date;

import javax.inject.Inject;

import uk.co.senab.photoview.PhotoViewAttacher;

public class MediaPreviewActivity extends AppCompatActivity {

    private static final String ARG_MEDIA_CONTENT_URI = "content_uri";
    private static final String ARG_MEDIA_LOCAL_ID = "media_local_id";
    private static final String ARG_IS_VIDEO = "is_video";

    private String mContentUri;
    private int mMediaId;
    private boolean mIsVideo;

    private ImageView mImageView;
    private VideoView mVideoView;
    private ViewGroup mMetadataView;

    @Inject MediaStore mMediaStore;
    @Inject FluxCImageLoader mImageLoader;

    /**
     * @param context     self explanatory
     * @param sourceView  optional imageView on calling activity which shows thumbnail of same media
     * @param contentUri  local content:// uri of media
     * @param isVideo     whether the passed media is a video - assumed to be an image otherwise
     */
    public static void showPreview(Context context,
                                   View sourceView,
                                   String contentUri,
                                   boolean isVideo) {
        Intent intent = new Intent(context, MediaPreviewActivity.class);
        intent.putExtra(ARG_MEDIA_CONTENT_URI, contentUri);
        intent.putExtra(ARG_IS_VIDEO, isVideo);
        showPreviewIntent(context, sourceView, intent);
    }

    /**
     * @param context     self explanatory
     * @param sourceView  optional imageView on calling activity which shows thumbnail of same media
     * @param mediaId     local ID in site's media library
     */
    public static void showPreview(Context context,
                                   View sourceView,
                                   int mediaId) {
        Intent intent = new Intent(context, MediaPreviewActivity.class);
        intent.putExtra(ARG_MEDIA_LOCAL_ID, mediaId);
        showPreviewIntent(context, sourceView, intent);
    }

    private static void showPreviewIntent(Context context, View sourceView, Intent intent) {
        ActivityOptionsCompat options;
        if (sourceView != null) {
            int startWidth = sourceView.getWidth();
            int startHeight = sourceView.getHeight();
            int startX = startWidth / 2;
            int startY = startHeight / 2;

            options = ActivityOptionsCompat.makeScaleUpAnimation(
                    sourceView,
                    startX,
                    startY,
                    startWidth,
                    startHeight);
        } else {
            options = ActivityOptionsCompat.makeBasic();
        }
        ActivityCompat.startActivity(context, intent, options.toBundle());
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ((WordPress) getApplication()).component().inject(this);

        setContentView(R.layout.media_preview_activity);
        mImageView = (ImageView) findViewById(R.id.image_preview);
        mVideoView = (VideoView) findViewById(R.id.video_preview);
        mMetadataView = (ViewGroup) findViewById(R.id.layout_metadata);

        if (savedInstanceState != null) {
            mContentUri = savedInstanceState.getString(ARG_MEDIA_CONTENT_URI);
            mMediaId = savedInstanceState.getInt(ARG_MEDIA_LOCAL_ID);
            mIsVideo = savedInstanceState.getBoolean(ARG_IS_VIDEO);
        } else {
            mContentUri = getIntent().getStringExtra(ARG_MEDIA_CONTENT_URI);
            mMediaId = getIntent().getIntExtra(ARG_MEDIA_LOCAL_ID, 0);
            mIsVideo = getIntent().getBooleanExtra(ARG_IS_VIDEO, false);
        }

        String mediaUri;
        if (!TextUtils.isEmpty(mContentUri)) {
            mediaUri = mContentUri;
        } else if (mMediaId != 0) {
            MediaModel media = mMediaStore.getMediaWithLocalId(mMediaId);
            if (media == null) {
                delayedFinish(true);
                return;
            }
            mIsVideo = media.isVideo();
            mediaUri = media.getUrl();
            showMetaData(media);
        } else {
            delayedFinish(true);
            return;
        }

        mImageView.setVisibility(mIsVideo ?  View.GONE : View.VISIBLE);
        mVideoView.setVisibility(mIsVideo ? View.VISIBLE : View.GONE);

        if (mIsVideo) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            playVideo(mediaUri);
        } else {
            loadImage(mediaUri);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(ARG_MEDIA_CONTENT_URI, mContentUri);
        outState.putInt(ARG_MEDIA_LOCAL_ID, mMediaId);
        outState.putBoolean(ARG_IS_VIDEO, mIsVideo);
    }

    private void delayedFinish(boolean showError) {
        if (showError) {
            ToastUtils.showToast(this, R.string.error_media_not_found);
        }
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 1500);
    }

    private void showProgress(boolean show) {
        findViewById(R.id.progress).setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /*
     * loads and displays a remote or local image
     */
    private void loadImage(@NonNull String mediaUri) {
        int width = DisplayUtils.getDisplayPixelWidth(this);
        int height = DisplayUtils.getDisplayPixelHeight(this);

        if (mediaUri.startsWith("http")) {
            showProgress(true);
            String imageUrl = PhotonUtils.getPhotonImageUrl(mediaUri, width, height);
            mImageLoader.get(imageUrl, new ImageLoader.ImageListener() {
                @Override
                public void onResponse(ImageLoader.ImageContainer response, boolean isImmediate) {
                    if (!isFinishing() && response.getBitmap() != null) {
                        showProgress(false);
                        mImageView.setImageBitmap(response.getBitmap());
                    }
                }
                @Override
                public void onErrorResponse(VolleyError error) {
                    AppLog.e(AppLog.T.MEDIA, error);
                    if (!isFinishing()) {
                        showProgress(false);
                        delayedFinish(true);
                    }
                }
            }, width, height);
        } else {
            byte[] bytes = ImageUtils.createThumbnailFromUri(this, Uri.parse(mediaUri), width, null, 0);
            if (bytes == null) {
                delayedFinish(true);
                return;
            }

            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp != null) {
                mImageView.setImageBitmap(bmp);
            } else {
                delayedFinish(true);
            }
        }

        // assign the photo attacher to enable pinch/zoom
        PhotoViewAttacher attacher = new PhotoViewAttacher(mImageView);
    }

    /*
     * loads and plays a remote or local video
     */
    private void playVideo(@NonNull String mediaUri) {
        final MediaController controls = new MediaController(this);
        mVideoView.setMediaController(controls);

        mVideoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                delayedFinish(false);
                return false;
            }
        });

        mVideoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                controls.show();
                mp.start();
            }
        });

        mVideoView.setVideoURI(Uri.parse(mediaUri));
        mVideoView.requestFocus();
    }
    
    private void showMetaData(@NonNull MediaModel media) {
        boolean isLocal = MediaUtils.isLocalFile(media.getUploadState());

        TextView captionView = (TextView) mMetadataView.findViewById(R.id.media_details_caption);
        TextView cescriptionView = (TextView) mMetadataView.findViewById(R.id.media_details_description);
        TextView dateView = (TextView) mMetadataView.findViewById(R.id.media_details_date);
        TextView fileNameView = (TextView) mMetadataView.findViewById(R.id.media_details_file_name);
        TextView fileTypeView = (TextView) mMetadataView.findViewById(R.id.media_details_file_type);

        if (TextUtils.isEmpty(media.getCaption())) {
            captionView.setVisibility(View.GONE);
        } else {
            captionView.setText(media.getCaption());
            captionView.setVisibility(View.VISIBLE);
        }

        if (TextUtils.isEmpty(media.getDescription())) {
            cescriptionView.setVisibility(View.GONE);
        } else {
            cescriptionView.setText(media.getDescription());
            cescriptionView.setVisibility(View.VISIBLE);
        }

        dateView.setText(getDisplayDate(media.getUploadDate()));

        TextView txtDateLabel = (TextView) mMetadataView.findViewById(R.id.media_details_date_label);
        txtDateLabel.setText(isLocal ? R.string.media_details_label_date_added : R.string.media_details_label_date_uploaded);

        String fileURL = media.getUrl();
        String fileName = media.getFileName();
        String imageUri = TextUtils.isEmpty(fileURL) ? media.getFilePath() : fileURL;
        boolean isValidImage = MediaUtils.isValidImage(imageUri);

        fileNameView.setText(fileName);

        float mediaWidth = media.getWidth();
        float mediaHeight = media.getHeight();

        // show dimens & file ext together
        String dimens =
                (mediaWidth > 0 && mediaHeight > 0) ? (int) mediaWidth + " x " + (int) mediaHeight : null;
        String fileExt =
                TextUtils.isEmpty(fileURL) ? null : fileURL.replaceAll(".*\\.(\\w+)$", "$1").toUpperCase();
        boolean hasDimens = !TextUtils.isEmpty(dimens);
        boolean hasExt = !TextUtils.isEmpty(fileExt);
        if (hasDimens & hasExt) {
            fileTypeView.setText(fileExt + ", " + dimens);
            fileTypeView.setVisibility(View.VISIBLE);
        } else if (hasExt) {
            fileTypeView.setText(fileExt);
            fileTypeView.setVisibility(View.VISIBLE);
        } else {
            fileTypeView.setVisibility(View.GONE);
        }

        fadeInMetadata();

        View.OnClickListener clickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fadeInMetadata();
            }
        };
        mMetadataView.setOnClickListener(clickListener);
        mImageView.setOnClickListener(clickListener);
    }

    private Runnable fadeOutRunnable = new Runnable() {
        @Override
        public void run() {
            if (mMetadataView.getVisibility() == View.VISIBLE) {
                AniUtils.fadeOut(mMetadataView, AniUtils.Duration.LONG);
            }
        }
    };

    private Runnable fadeInRunnable = new Runnable() {
        @Override
        public void run() {
            if (mMetadataView.getVisibility() != View.VISIBLE) {
                AniUtils.fadeIn(mMetadataView, AniUtils.Duration.LONG);
                mMetadataView.postDelayed(fadeOutRunnable, 3000);
            }
        }
    };

    private void fadeInMetadata() {
        mMetadataView.post(fadeInRunnable);
    }

    /*
     * returns the passed string formatted as a short date if it's valid ISO 8601 date,
     * otherwise returns the passed string
     */
    private String getDisplayDate(String dateString) {
        if (dateString != null) {
            Date date = DateTimeUtils.dateFromIso8601(dateString);
            if (date != null) {
                return SimpleDateFormat.getDateInstance().format(date);
            }
        }
        return dateString;
    }
}