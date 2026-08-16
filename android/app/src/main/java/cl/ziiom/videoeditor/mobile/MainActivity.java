package cl.ziiom.videoeditor.mobile;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.DefaultGainProvider;
import androidx.media3.common.audio.GainProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@UnstableApi
public class MainActivity extends AppCompatActivity {

    private static class ClipModel {
        Uri uri;
        String name;
        long durationMs;
        long trimStartMs;
        long trimEndMs;
        float speed = 1f;
        float volume = 1f;
        float rotation = 0f;

        ClipModel(Uri uri, String name, long durationMs) {
            this.uri = uri;
            this.name = name;
            this.durationMs = Math.max(1, durationMs);
            this.trimStartMs = 0;
            this.trimEndMs = this.durationMs;
        }
    }

    private PlayerView playerView;
    private ExoPlayer player;
    private TextView emptyHint;
    private TextView timeLabel;
    private TextView selectedClipLabel;
    private TextView trimStartLabel;
    private TextView trimEndLabel;
    private TextView audioLabel;
    private TextView statusLabel;
    private SeekBar playbackSeek;
    private SeekBar trimStartSeek;
    private SeekBar trimEndSeek;
    private SeekBar volumeSeek;
    private Spinner speedSpinner;
    private Button rotateBtn;
    private Button playBtn;
    private Button exportBtn;
    private LinearLayout clipStrip;
    private ProgressBar exportProgress;

    private final List<ClipModel> clips = new ArrayList<>();
    private int selectedIndex = -1;
    private Uri backgroundAudioUri;
    private String backgroundAudioName;
    private boolean userSeeking = false;
    private Transformer transformer;
    private File lastExportedFile;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable playbackTicker = new Runnable() {
        @Override
        public void run() {
            ClipModel clip = selectedClip();
            if (clip != null && player != null) {
                long position = player.getCurrentPosition();
                if (player.isPlaying() && position >= clip.trimEndMs) {
                    player.pause();
                    player.seekTo(clip.trimEndMs);
                }
                if (!userSeeking) playbackSeek.setProgress(positionToProgress(position, clip.durationMs));
                timeLabel.setText(formatTime(position) + " / " + formatTime(clip.durationMs));
                playBtn.setText(player.isPlaying() ? "❚❚" : "▶");
            }
            handler.postDelayed(this, 100);
        }
    };

    private final ActivityResultLauncher<String[]> videoPicker = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(),
            uris -> {
                if (uris == null || uris.isEmpty()) return;
                for (Uri uri : uris) {
                    persistReadPermission(uri);
                    if (!containsUri(uri)) clips.add(new ClipModel(uri, queryDisplayName(uri), queryDuration(uri)));
                }
                if (selectedIndex < 0 && !clips.isEmpty()) selectedIndex = 0;
                renderClipStrip();
                loadSelectedClip();
                setStatus(clips.size() + (clips.size() == 1 ? " clip listo" : " clips listos"));
            }
    );

    private final ActivityResultLauncher<String[]> audioPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri == null) return;
                persistReadPermission(uri);
                backgroundAudioUri = uri;
                backgroundAudioName = queryDisplayName(uri);
                audioLabel.setText("Música: " + backgroundAudioName);
                setStatus("Música de fondo agregada");
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupPlayer();
        setupControls();
        handler.post(playbackTicker);
    }

    private void bindViews() {
        playerView = findViewById(R.id.playerView);
        emptyHint = findViewById(R.id.emptyHint);
        timeLabel = findViewById(R.id.timeLabel);
        selectedClipLabel = findViewById(R.id.selectedClipLabel);
        trimStartLabel = findViewById(R.id.trimStartLabel);
        trimEndLabel = findViewById(R.id.trimEndLabel);
        audioLabel = findViewById(R.id.audioLabel);
        statusLabel = findViewById(R.id.statusLabel);
        playbackSeek = findViewById(R.id.playbackSeek);
        trimStartSeek = findViewById(R.id.trimStartSeek);
        trimEndSeek = findViewById(R.id.trimEndSeek);
        volumeSeek = findViewById(R.id.volumeSeek);
        speedSpinner = findViewById(R.id.speedSpinner);
        rotateBtn = findViewById(R.id.rotateBtn);
        playBtn = findViewById(R.id.playBtn);
        exportBtn = findViewById(R.id.exportBtn);
        clipStrip = findViewById(R.id.clipStrip);
        exportProgress = findViewById(R.id.exportProgress);
    }

    private void setupPlayer() {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                playBtn.setText(isPlaying ? "❚❚" : "▶");
            }
        });
    }

    private void setupControls() {
        Button importVideoBtn = findViewById(R.id.importVideoBtn);
        Button backBtn = findViewById(R.id.backBtn);
        Button forwardBtn = findViewById(R.id.forwardBtn);
        Button addAudioBtn = findViewById(R.id.addAudioBtn);
        Button deleteBtn = findViewById(R.id.deleteBtn);

        importVideoBtn.setOnClickListener(v -> videoPicker.launch(new String[]{"video/*"}));
        addAudioBtn.setOnClickListener(v -> audioPicker.launch(new String[]{"audio/*"}));
        exportBtn.setOnClickListener(v -> exportProject());

        playBtn.setOnClickListener(v -> {
            ClipModel clip = selectedClip();
            if (clip == null) return;
            if (player.isPlaying()) player.pause();
            else {
                long p = player.getCurrentPosition();
                if (p < clip.trimStartMs || p >= clip.trimEndMs) player.seekTo(clip.trimStartMs);
                player.play();
            }
        });
        backBtn.setOnClickListener(v -> seekBy(-1000));
        forwardBtn.setOnClickListener(v -> seekBy(1000));

        playbackSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                ClipModel clip = selectedClip();
                if (clip != null) player.seekTo(progressToPosition(seekBar.getProgress(), clip.durationMs));
                userSeeking = false;
            }
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    ClipModel clip = selectedClip();
                    if (clip != null) timeLabel.setText(formatTime(progressToPosition(progress, clip.durationMs)) + " / " + formatTime(clip.durationMs));
                }
            }
        });

        trimStartSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                ClipModel clip = selectedClip();
                if (clip == null) return;
                long proposed = progressToPosition(progress, clip.durationMs);
                clip.trimStartMs = Math.min(proposed, Math.max(0, clip.trimEndMs - 100));
                trimStartLabel.setText("Inicio: " + formatTimePrecise(clip.trimStartMs));
                player.seekTo(clip.trimStartMs);
            }
        });

        trimEndSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                ClipModel clip = selectedClip();
                if (clip == null) return;
                long proposed = progressToPosition(progress, clip.durationMs);
                clip.trimEndMs = Math.max(proposed, Math.min(clip.durationMs, clip.trimStartMs + 100));
                trimEndLabel.setText("Final: " + formatTimePrecise(clip.trimEndMs));
                if (player.getCurrentPosition() > clip.trimEndMs) player.seekTo(clip.trimEndMs);
            }
        });

        String[] speeds = {"0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x"};
        ArrayAdapter<String> speedAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, speeds);
        speedSpinner.setAdapter(speedAdapter);
        speedSpinner.setSelection(2);
        speedSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                ClipModel clip = selectedClip();
                if (clip == null) return;
                clip.speed = parseSpeed(speeds[position]);
                player.setPlaybackSpeed(clip.speed);
                renderClipStrip();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                ClipModel clip = selectedClip();
                if (clip == null) return;
                clip.volume = progress / 100f;
                player.setVolume(clip.volume);
            }
        });

        rotateBtn.setOnClickListener(v -> {
            ClipModel clip = selectedClip();
            if (clip == null) return;
            clip.rotation = (clip.rotation + 90f) % 360f;
            rotateBtn.setText((int) clip.rotation + "°");
            applyPreviewRotation(clip.rotation);
        });

        deleteBtn.setOnClickListener(v -> {
            if (selectedIndex < 0 || selectedIndex >= clips.size()) return;
            clips.remove(selectedIndex);
            if (clips.isEmpty()) selectedIndex = -1;
            else selectedIndex = Math.min(selectedIndex, clips.size() - 1);
            renderClipStrip();
            loadSelectedClip();
        });
    }

    private void renderClipStrip() {
        clipStrip.removeAllViews();
        for (int i = 0; i < clips.size(); i++) {
            ClipModel clip = clips.get(i);
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText((i + 1) + ". " + shortName(clip.name) + "\n" + formatTime(clip.trimEndMs - clip.trimStartMs) + " · " + formatSpeed(clip.speed));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(178), dp(60));
            lp.setMarginEnd(dp(6));
            b.setLayoutParams(lp);
            b.setAlpha(i == selectedIndex ? 1f : 0.72f);
            final int index = i;
            b.setOnClickListener(v -> {
                selectedIndex = index;
                renderClipStrip();
                loadSelectedClip();
            });
            clipStrip.addView(b);
        }
    }

    private void loadSelectedClip() {
        ClipModel clip = selectedClip();
        if (clip == null) {
            player.stop();
            player.clearMediaItems();
            playerView.setVisibility(View.INVISIBLE);
            emptyHint.setVisibility(View.VISIBLE);
            selectedClipLabel.setText("Sin clip seleccionado");
            timeLabel.setText("00:00 / 00:00");
            return;
        }
        emptyHint.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        selectedClipLabel.setText(clip.name);
        trimStartSeek.setProgress(positionToProgress(clip.trimStartMs, clip.durationMs));
        trimEndSeek.setProgress(positionToProgress(clip.trimEndMs, clip.durationMs));
        trimStartLabel.setText("Inicio: " + formatTimePrecise(clip.trimStartMs));
        trimEndLabel.setText("Final: " + formatTimePrecise(clip.trimEndMs));
        volumeSeek.setProgress(Math.round(clip.volume * 100));
        rotateBtn.setText((int) clip.rotation + "°");
        speedSpinner.setSelection(speedIndex(clip.speed));
        player.setMediaItem(MediaItem.fromUri(clip.uri));
        player.prepare();
        player.seekTo(clip.trimStartMs);
        player.setPlaybackSpeed(clip.speed);
        player.setVolume(clip.volume);
        applyPreviewRotation(clip.rotation);
    }

    private void applyPreviewRotation(float rotation) {
        playerView.setRotation(rotation);
        float scale = (rotation == 90f || rotation == 270f) ? 0.72f : 1f;
        playerView.setScaleX(scale);
        playerView.setScaleY(scale);
    }

    private void seekBy(long deltaMs) {
        ClipModel clip = selectedClip();
        if (clip == null) return;
        long next = Math.max(clip.trimStartMs, Math.min(clip.trimEndMs, player.getCurrentPosition() + deltaMs));
        player.seekTo(next);
    }

    private ClipModel selectedClip() {
        if (selectedIndex < 0 || selectedIndex >= clips.size()) return null;
        return clips.get(selectedIndex);
    }

    private boolean containsUri(Uri uri) {
        for (ClipModel c : clips) if (c.uri.equals(uri)) return true;
        return false;
    }

    private void exportProject() {
        if (clips.isEmpty()) {
            toast("Agrega al menos un video");
            return;
        }
        exportBtn.setEnabled(false);
        exportProgress.setVisibility(View.VISIBLE);
        exportProgress.setProgress(0);
        setStatus("Preparando exportación…");

        List<EditedMediaItem> editedVideos = new ArrayList<>();
        for (ClipModel clip : clips) {
            MediaItem.ClippingConfiguration clipping = new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartMs)
                    .setEndPositionMs(clip.trimEndMs)
                    .build();
            MediaItem mediaItem = new MediaItem.Builder().setUri(clip.uri).setClippingConfiguration(clipping).build();

            List<AudioProcessor> audioProcessors = new ArrayList<>();
            if (clip.volume < 0.999f) {
                DefaultGainProvider provider = new DefaultGainProvider.Builder(Math.max(0f, Math.min(1f, clip.volume))).build();
                audioProcessors.add(new GainProcessor(provider));
            }
            List<Effect> videoEffects = new ArrayList<>();
            if (Math.abs(clip.rotation) > 0.01f) {
                videoEffects.add(new ScaleAndRotateTransformation.Builder().setRotationDegrees(clip.rotation).build());
            }

            EditedMediaItem.Builder editedBuilder = new EditedMediaItem.Builder(mediaItem)
                    .setEffects(new Effects(audioProcessors, videoEffects))
                    .setFrameRate(30);
            if (Math.abs(clip.speed - 1f) > 0.001f) {
                final float speed = clip.speed;
                editedBuilder.setSpeed(new SpeedProvider() {
                    @Override public float getSpeed(long timeUs) { return speed; }
                    @Override public long getNextSpeedChangeTimeUs(long timeUs) { return C.TIME_UNSET; }
                });
            }
            editedVideos.add(editedBuilder.build());
        }

        EditedMediaItemSequence videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(editedVideos);
        Composition composition;
        if (backgroundAudioUri != null) {
            EditedMediaItem bgAudio = new EditedMediaItem.Builder(MediaItem.fromUri(backgroundAudioUri)).build();
            EditedMediaItemSequence audioSequence = EditedMediaItemSequence.withAudioFrom(Collections.singletonList(bgAudio))
                    .buildUpon().setIsLooping(true).build();
            composition = new Composition.Builder(videoSequence, audioSequence).build();
        } else {
            composition = new Composition.Builder(videoSequence).build();
        }

        File movies = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (movies == null) movies = getFilesDir();
        File exportDir = new File(movies, "ZiiomVideoEditor");
        if (!exportDir.exists()) exportDir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File output = new File(exportDir, "Ziiom-" + stamp + ".mp4");
        lastExportedFile = output;

        transformer = new Transformer.Builder(this)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(new Transformer.Listener() {
                    @Override public void onCompleted(@NonNull Composition composition, @NonNull ExportResult exportResult) {
                        exportBtn.setEnabled(true);
                        exportProgress.setProgress(100);
                        setStatus("Exportación completada");
                        Uri galleryUri = publishToGallery(output);
                        toast(galleryUri != null ? "Video guardado en la galería" : "Video exportado: " + output.getName());
                    }
                    @Override public void onError(@NonNull Composition composition, @NonNull ExportResult exportResult, @NonNull ExportException exportException) {
                        exportBtn.setEnabled(true);
                        exportProgress.setVisibility(View.GONE);
                        setStatus("Error al exportar");
                        toast("No se pudo exportar: " + exportException.getMessage());
                    }
                }).build();

        transformer.start(composition, output.getAbsolutePath());
        pollExportProgress();
    }

    private void pollExportProgress() {
        if (transformer == null) return;
        ProgressHolder holder = new ProgressHolder();
        int state = transformer.getProgress(holder);
        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
            exportProgress.setProgress(holder.progress);
            setStatus("Exportando… " + holder.progress + "%");
        }
        if (exportBtn.isEnabled()) return;
        handler.postDelayed(this::pollExportProgress, 500);
    }

    private Uri publishToGallery(File source) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !source.exists()) return null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, source.getName());
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Ziiom Video Editor");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            ContentResolver resolver = getContentResolver();
            Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (InputStream in = new FileInputStream(source); OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) return null;
                byte[] buffer = new byte[1024 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private void shareLastExport() {
        if (lastExportedFile == null || !lastExportedFile.exists()) return;
        Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", lastExportedFile);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("video/mp4");
        send.putExtra(Intent.EXTRA_STREAM, contentUri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Compartir video"));
    }

    private void persistReadPermission(Uri uri) {
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) {}
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {}
        return "Video";
    }

    private long queryDuration(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (value != null) return Math.max(1, Long.parseLong(value));
        } catch (Exception ignored) {
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
        return 1000;
    }

    private int positionToProgress(long position, long duration) {
        if (duration <= 0) return 0;
        return (int) Math.max(0, Math.min(1000, Math.round(position * 1000.0 / duration)));
    }
    private long progressToPosition(int progress, long duration) { return Math.round((progress / 1000.0) * duration); }

    private int speedIndex(float speed) {
        float[] values = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        int best = 2;
        float diff = Float.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            float d = Math.abs(values[i] - speed);
            if (d < diff) { diff = d; best = i; }
        }
        return best;
    }

    private float parseSpeed(String s) { return Float.parseFloat(s.replace("x", "")); }
    private String formatSpeed(float speed) {
        if (Math.abs(speed - Math.round(speed)) < 0.001f) return Math.round(speed) + "x";
        return String.format(Locale.US, "%.2gx", speed);
    }
    private String shortName(String name) {
        if (name == null) return "Video";
        return name.length() > 20 ? name.substring(0, 18) + "…" : name;
    }
    private String formatTime(long ms) {
        long total = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
    }
    private String formatTimePrecise(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000;
        long hundredths = (Math.max(0, ms) % 1000) / 10;
        return String.format(Locale.US, "%02d:%02d.%02d", totalSeconds / 60, totalSeconds % 60, hundredths);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void setStatus(String text) { statusLabel.setText(text); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (transformer != null && !exportBtn.isEnabled()) transformer.cancel();
        if (player != null) player.release();
        super.onDestroy();
    }
}
