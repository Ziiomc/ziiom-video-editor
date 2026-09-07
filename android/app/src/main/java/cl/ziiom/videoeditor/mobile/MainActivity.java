package cl.ziiom.videoeditor.mobile;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
public class MainActivity extends AppCompatActivity {
    private final int BG = Color.rgb(7,10,16);
    private final int TEXT = Color.rgb(238,244,255);
    private final int MUTED = Color.rgb(132,145,164);

    private VisualizerView visualizerView;
    private TextView songLabel, imageLabel, centerImageLabel, timeLabel, statusLabel;
    private TextView sensitivityValue, darknessValue, zoomValue, centerSizeValue;
    private TextView thicknessValue, rotationValue, vignetteValue;
    private SeekBar playbackSeek, sensitivitySeek, darknessSeek, zoomSeek, centerSizeSeek;
    private SeekBar thicknessSeek, rotationSeek, vignetteSeek;
    private Spinner presetSpinner, modeSpinner, paletteSpinner, resolutionSpinner, fpsSpinner;
    private CheckBox glowCheck;
    private Button playBtn, exportBtn;
    private ProgressBar workProgress;

    private ExoPlayer player;
    private Transformer transformer;
    private Uri audioUri, imageUri, centerImageUri;
    private Bitmap backgroundBitmap, centerBitmap;
    private AudioAnalysis analysis;
    private final VisualizerConfig config = new VisualizerConfig();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean userSeeking;
    private boolean applyingPreset;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player != null && visualizerView != null) {
                long pos = Math.max(0, player.getCurrentPosition());
                visualizerView.setPositionMs(pos);
                if (!userSeeking && analysis != null && analysis.durationMs > 0) {
                    playbackSeek.setProgress((int)Math.min(1000, pos * 1000L / analysis.durationMs));
                }
                timeLabel.setText(formatTime(pos) + " / " + formatTime(analysis == null ? 0 : analysis.durationMs));
                playBtn.setText(player.isPlaying() ? "❚❚" : "▶");
            }
            handler.postDelayed(this, 16);
        }
    };

    private final ActivityResultLauncher<String[]> audioPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                persistReadPermission(uri);
                audioUri = uri;
                songLabel.setText("Canción: " + queryDisplayName(uri));
                analyzeSong(uri);
            });

    private final ActivityResultLauncher<String[]> imagePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                persistReadPermission(uri);
                imageUri = uri;
                imageLabel.setText("Fondo: " + queryDisplayName(uri));
                loadBackground(uri);
            });

    private final ActivityResultLauncher<String[]> centerImagePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                persistReadPermission(uri);
                centerImageUri = uri;
                centerImageLabel.setText("Imagen central: " + queryDisplayName(uri));
                loadCenterImage(uri);
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) { player.pause(); player.seekTo(0); }
            }
        });
        setupControls();
        visualizerView.setConfig(config);
        handler.post(ticker);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = vertical();
        root.setPadding(dp(14), dp(14), dp(14), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        root.addView(text("PULSE CANVAS", 24, TEXT, true));
        root.addView(text("MUSIC VISUALIZER · PRO", 12, Color.rgb(86,183,255), true), lp(-1,-2,0,1,0,5));
        root.addView(text("Portada dentro del espectro · reacción a graves · presets · Full HD", 13, MUTED, false), lp(-1,-2,0,0,0,12));

        LinearLayout importRow = horizontal();
        Button pickSongBtn = button("+ Canción");
        Button pickImageBtn = button("+ Fondo");
        importRow.addView(pickSongBtn, weightLp());
        LinearLayout.LayoutParams second = weightLp(); second.setMarginStart(dp(8));
        importRow.addView(pickImageBtn, second);
        root.addView(importRow, lp(-1, -2, 0, 0, 0, 6));

        Button pickCenterBtn = button("+ Imagen dentro del espectro");
        root.addView(pickCenterBtn, lp(-1, dp(50), 0, 0, 0, 7));

        songLabel = text("Canción: sin seleccionar", 12, MUTED, false);
        imageLabel = text("Fondo: sin seleccionar", 12, MUTED, false);
        centerImageLabel = text("Imagen central: opcional · usa el fondo si no eliges otra", 12, MUTED, false);
        root.addView(songLabel); root.addView(imageLabel); root.addView(centerImageLabel, lp(-1,-2,0,0,0,10));

        visualizerView = new VisualizerView(this);
        visualizerView.setBackgroundColor(Color.BLACK);
        root.addView(visualizerView, lp(-1, dp(210), 0, 0, 0, 8));

        LinearLayout transport = horizontal(); transport.setGravity(Gravity.CENTER_VERTICAL);
        playBtn = button("▶"); playBtn.setEnabled(false);
        timeLabel = text("00:00 / 00:00", 12, MUTED, false); timeLabel.setGravity(Gravity.END);
        transport.addView(playBtn, new LinearLayout.LayoutParams(dp(58), dp(48)));
        LinearLayout.LayoutParams timeLp = weightLp(); timeLp.setMarginStart(dp(8)); transport.addView(timeLabel, timeLp);
        root.addView(transport);
        playbackSeek = new SeekBar(this); playbackSeek.setMax(1000); root.addView(playbackSeek, lp(-1,-2,0,0,0,8));

        root.addView(section("PRESETS"));
        presetSpinner = new Spinner(this); root.addView(presetSpinner, lp(-1,dp(50),0,0,0,6));

        root.addView(section("ESTILO"));
        modeSpinner = new Spinner(this); root.addView(modeSpinner, lp(-1,dp(50),0,0,0,4));
        paletteSpinner = new Spinner(this); root.addView(paletteSpinner, lp(-1,dp(50),0,0,0,8));

        sensitivityValue = addSliderSection(root,"Sensibilidad","1.35x");
        sensitivitySeek = new SeekBar(this); root.addView(sensitivitySeek);
        darknessValue = addSliderSection(root,"Oscurecimiento con bajos","72%");
        darknessSeek = new SeekBar(this); root.addView(darknessSeek);
        zoomValue = addSliderSection(root,"Pulso / zoom","85%");
        zoomSeek = new SeekBar(this); root.addView(zoomSeek);
        centerSizeValue = addSliderSection(root,"Tamaño de imagen central","58%");
        centerSizeSeek = new SeekBar(this); root.addView(centerSizeSeek);
        thicknessValue = addSliderSection(root,"Grosor del espectro","1.00x");
        thicknessSeek = new SeekBar(this); root.addView(thicknessSeek);
        rotationValue = addSliderSection(root,"Movimiento / rotación","1.00x");
        rotationSeek = new SeekBar(this); root.addView(rotationSeek);
        vignetteValue = addSliderSection(root,"Viñeta cinematográfica","35%");
        vignetteSeek = new SeekBar(this); root.addView(vignetteSeek);

        glowCheck = new CheckBox(this);
        glowCheck.setText("Glow / halo luminoso"); glowCheck.setTextColor(TEXT);
        root.addView(glowCheck, lp(-1,-2,0,2,0,8));

        root.addView(section("EXPORTACIÓN"));
        resolutionSpinner = new Spinner(this); root.addView(resolutionSpinner, lp(-1,dp(50),0,0,0,4));
        fpsSpinner = new Spinner(this); root.addView(fpsSpinner, lp(-1,dp(50),0,0,0,8));

        exportBtn = button("EXPORTAR VISUAL MP4"); exportBtn.setEnabled(false);
        root.addView(exportBtn, lp(-1,dp(58),0,4,0,8));

        workProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        workProgress.setMax(100); workProgress.setVisibility(View.GONE);
        root.addView(workProgress, lp(-1,-2,0,0,0,6));
        statusLabel = text("Selecciona una canción y un fondo para comenzar.",12,MUTED,false);
        root.addView(statusLabel);

        pickSongBtn.setOnClickListener(v -> audioPicker.launch(new String[]{"audio/*"}));
        pickImageBtn.setOnClickListener(v -> imagePicker.launch(new String[]{"image/*"}));
        pickCenterBtn.setOnClickListener(v -> centerImagePicker.launch(new String[]{"image/*"}));
        return scroll;
    }

    private TextView section(String value) {
        TextView v = text(value,12,Color.rgb(105,190,255),true);
        v.setLetterSpacing(0.08f);
        return v;
    }

    private TextView addSliderSection(LinearLayout root,String label,String initial) {
        LinearLayout row=horizontal(); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l=text(label,13,MUTED,false);
        TextView v=text(initial,13,TEXT,false); v.setGravity(Gravity.END);
        row.addView(l,weightLp()); row.addView(v,new LinearLayout.LayoutParams(-2,-2)); root.addView(row);
        return v;
    }

    private void setupControls() {
        playBtn.setOnClickListener(v -> togglePlayback());
        exportBtn.setOnClickListener(v -> exportVideo());

        playbackSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar s){userSeeking=true;}
            @Override public void onStopTrackingTouch(SeekBar s){if(analysis!=null)player.seekTo(s.getProgress()*analysis.durationMs/1000L);userSeeking=false;}
            @Override public void onProgressChanged(SeekBar s,int p,boolean fromUser){if(fromUser&&analysis!=null)visualizerView.setPositionMs(p*analysis.durationMs/1000L);}
        });

        String[] presets={"Personalizado","PHONK · Triángulo","NEON CLUB · Círculo","MINIMAL · Anillo","FIRE · Diamante","GOLD · Anillo"};
        presetSpinner.setAdapter(adapter(presets));

        String[] modes={"Triángulo · imagen dentro","Círculo · imagen dentro","Diamante · imagen dentro","Anillo 360°","Barras + portada","Onda + portada","Espejo + portada","Partículas + portada"};
        modeSpinner.setAdapter(adapter(modes));

        String[] palettes={"Ice Blue","Neon Cyan / Violeta","Phonk Rosa / Violeta","Fire Amarillo / Rojo","Gold","White"};
        paletteSpinner.setAdapter(adapter(palettes));

        String[] resolutions={"1280 × 720 · Horizontal","720 × 1280 · Vertical","1920 × 1080 · Full HD","1080 × 1920 · Full HD Vertical"};
        resolutionSpinner.setAdapter(adapter(resolutions));
        String[] fps={"30 FPS · estándar","60 FPS · ultra fluido"};
        fpsSpinner.setAdapter(adapter(fps));

        sensitivitySeek.setMax(200); sensitivitySeek.setProgress(85);
        darknessSeek.setMax(100); darknessSeek.setProgress(72);
        zoomSeek.setMax(100); zoomSeek.setProgress(85);
        centerSizeSeek.setMax(100); centerSizeSeek.setProgress(58);
        thicknessSeek.setMax(100); thicknessSeek.setProgress(25);
        rotationSeek.setMax(100); rotationSeek.setProgress(46);
        vignetteSeek.setMax(100); vignetteSeek.setProgress(35);
        glowCheck.setChecked(true);

        SeekBar.OnSeekBarChangeListener fx=new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
            @Override public void onProgressChanged(SeekBar s,int p,boolean fromUser){if(!applyingPreset){presetSpinner.setSelection(0);updateConfig();}}
        };
        sensitivitySeek.setOnSeekBarChangeListener(fx); darknessSeek.setOnSeekBarChangeListener(fx);
        zoomSeek.setOnSeekBarChangeListener(fx); centerSizeSeek.setOnSeekBarChangeListener(fx);
        thicknessSeek.setOnSeekBarChangeListener(fx); rotationSeek.setOnSeekBarChangeListener(fx);
        vignetteSeek.setOnSeekBarChangeListener(fx);

        modeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){config.mode=VisualizerConfig.Mode.values()[pos];if(!applyingPreset)presetSpinner.setSelection(0);visualizerView.invalidate();}
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        paletteSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){config.palette=VisualizerConfig.Palette.values()[pos];if(!applyingPreset)presetSpinner.setSelection(0);visualizerView.invalidate();}
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        resolutionSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){resizePreview(pos==0||pos==2);}
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        glowCheck.setOnCheckedChangeListener((b,c)->{config.glow=c;if(!applyingPreset)presetSpinner.setSelection(0);visualizerView.invalidate();});

        presetSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){if(pos>0)applyPreset(pos);}
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        updateConfig();
    }

    private ArrayAdapter<String> adapter(String[] values){
        return new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values);
    }

    private void applyPreset(int preset) {
        applyingPreset=true;
        switch(preset){
            case 1: // PHONK
                modeSpinner.setSelection(0); paletteSpinner.setSelection(2);
                sensitivitySeek.setProgress(112); darknessSeek.setProgress(82); zoomSeek.setProgress(100);
                centerSizeSeek.setProgress(62); thicknessSeek.setProgress(52); rotationSeek.setProgress(58); vignetteSeek.setProgress(58); glowCheck.setChecked(true);
                break;
            case 2: // NEON
                modeSpinner.setSelection(1); paletteSpinner.setSelection(1);
                sensitivitySeek.setProgress(102); darknessSeek.setProgress(58); zoomSeek.setProgress(88);
                centerSizeSeek.setProgress(60); thicknessSeek.setProgress(43); rotationSeek.setProgress(70); vignetteSeek.setProgress(42); glowCheck.setChecked(true);
                break;
            case 3: // MINIMAL
                modeSpinner.setSelection(3); paletteSpinner.setSelection(5);
                sensitivitySeek.setProgress(62); darknessSeek.setProgress(32); zoomSeek.setProgress(38);
                centerSizeSeek.setProgress(56); thicknessSeek.setProgress(18); rotationSeek.setProgress(22); vignetteSeek.setProgress(22); glowCheck.setChecked(false);
                break;
            case 4: // FIRE
                modeSpinner.setSelection(2); paletteSpinner.setSelection(3);
                sensitivitySeek.setProgress(108); darknessSeek.setProgress(78); zoomSeek.setProgress(96);
                centerSizeSeek.setProgress(60); thicknessSeek.setProgress(48); rotationSeek.setProgress(52); vignetteSeek.setProgress(52); glowCheck.setChecked(true);
                break;
            case 5: // GOLD
                modeSpinner.setSelection(3); paletteSpinner.setSelection(4);
                sensitivitySeek.setProgress(86); darknessSeek.setProgress(54); zoomSeek.setProgress(72);
                centerSizeSeek.setProgress(58); thicknessSeek.setProgress(32); rotationSeek.setProgress(38); vignetteSeek.setProgress(38); glowCheck.setChecked(true);
                break;
        }
        updateConfig();
        applyingPreset=false;
        visualizerView.invalidate();
    }

    private void updateConfig() {
        config.sensitivity=.5f+sensitivitySeek.getProgress()/100f;
        config.darkness=darknessSeek.getProgress()/100f;
        config.zoom=zoomSeek.getProgress()/100f;
        config.centerSize=.34f+centerSizeSeek.getProgress()/100f*.42f;
        config.thickness=.60f+thicknessSeek.getProgress()/100f*1.60f;
        config.rotationSpeed=.15f+rotationSeek.getProgress()/100f*1.85f;
        config.vignette=vignetteSeek.getProgress()/100f;
        config.glow=glowCheck.isChecked();

        sensitivityValue.setText(String.format(Locale.US,"%.2fx",config.sensitivity));
        darknessValue.setText(darknessSeek.getProgress()+"%");
        zoomValue.setText(zoomSeek.getProgress()+"%");
        centerSizeValue.setText(centerSizeSeek.getProgress()+"%");
        thicknessValue.setText(String.format(Locale.US,"%.2fx",config.thickness));
        rotationValue.setText(String.format(Locale.US,"%.2fx",config.rotationSpeed));
        vignetteValue.setText(vignetteSeek.getProgress()+"%");
        visualizerView.invalidate();
    }

    private void resizePreview(boolean horizontal){
        visualizerView.getLayoutParams().height=dp(horizontal?210:390); visualizerView.requestLayout();
    }

    private void togglePlayback(){
        if(analysis==null)return;
        if(player.isPlaying())player.pause();else{if(player.getCurrentPosition()>=analysis.durationMs-100)player.seekTo(0);player.play();}
    }

    private void analyzeSong(Uri uri){
        analysis=null;visualizerView.setAnalysis(null);player.pause();playBtn.setEnabled(false);exportBtn.setEnabled(false);
        workProgress.setVisibility(View.VISIBLE);workProgress.setProgress(0);statusLabel.setText("Analizando frecuencias y graves…");
        executor.execute(()->{
            try{
                AudioAnalysis result=AudioAnalyzer.analyze(this,uri,p->runOnUiThread(()->{workProgress.setProgress(p);statusLabel.setText("Analizando canción… "+p+"%");}));
                runOnUiThread(()->{analysis=result;visualizerView.setAnalysis(result);player.setMediaItem(MediaItem.fromUri(uri));player.prepare();player.seekTo(0);playBtn.setEnabled(true);workProgress.setVisibility(View.GONE);statusLabel.setText("Listo. Prueba un preset o personaliza el visual.");refreshReady();});
            }catch(Exception e){runOnUiThread(()->{workProgress.setVisibility(View.GONE);statusLabel.setText("No se pudo analizar la canción");toast(e.getMessage()==null?"Audio no compatible":e.getMessage());});}
        });
    }

    private Bitmap readBitmap(Uri uri)throws Exception{
        try(InputStream in=getContentResolver().openInputStream(uri)){Bitmap b=BitmapFactory.decodeStream(in);if(b==null)throw new IllegalArgumentException("Imagen no compatible");return b;}
    }
    private void loadBackground(Uri uri){try{backgroundBitmap=readBitmap(uri);visualizerView.setBackgroundBitmap(backgroundBitmap);refreshReady();}catch(Exception e){toast("No se pudo abrir la imagen de fondo");}}
    private void loadCenterImage(Uri uri){try{centerBitmap=readBitmap(uri);visualizerView.setCenterBitmap(centerBitmap);statusLabel.setText("Imagen central lista dentro del espectro.");}catch(Exception e){toast("No se pudo abrir la imagen central");}}
    private void refreshReady(){exportBtn.setEnabled(audioUri!=null&&imageUri!=null&&analysis!=null&&transformer==null);}

    private void exportVideo(){
        if(audioUri==null||imageUri==null||analysis==null){toast("Selecciona una canción y una imagen de fondo primero");return;}
        int[] size=exportSize(resolutionSpinner.getSelectedItemPosition());
        final int width=size[0],height=size[1];
        final int exportFps=fpsSpinner.getSelectedItemPosition()==1?60:30;
        final AudioAnalysis exportAnalysis=analysis;
        final VisualizerConfig exportConfig=config.copy();
        final Bitmap exportCenter=centerBitmap!=null?centerBitmap:backgroundBitmap;

        player.pause();playBtn.setEnabled(false);exportBtn.setEnabled(false);workProgress.setVisibility(View.VISIBLE);workProgress.setProgress(0);
        statusLabel.setText("Preparando "+width+"×"+height+" · "+exportFps+" FPS…");

        MediaItem imageItem=new MediaItem.Builder().setUri(imageUri).setImageDurationMs(exportAnalysis.durationMs).build();
        List<Effect> videoEffects=new ArrayList<>();
        videoEffects.add(Presentation.createForWidthAndHeight(width,height,Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
        videoEffects.add(new MatrixTransformation(){@Override public Matrix getMatrix(long timeUs){float bass=Math.min(1f,exportAnalysis.bassAt(timeUs/1000L)*exportConfig.sensitivity);float s=1f+.060f*exportConfig.zoom*bass;Matrix m=new Matrix();m.postScale(s,s);return m;}});
        videoEffects.add(new OverlayEffect(Collections.<TextureOverlay>singletonList(new VisualizerOverlay(exportAnalysis,exportConfig,exportCenter))));

        EditedMediaItem visual=new EditedMediaItem.Builder(imageItem).setFrameRate(exportFps).setEffects(new Effects(Collections.<AudioProcessor>emptyList(),videoEffects)).build();
        EditedMediaItem audio=new EditedMediaItem.Builder(MediaItem.fromUri(audioUri)).build();
        EditedMediaItemSequence videoSeq=EditedMediaItemSequence.withVideoFrom(Collections.singletonList(visual));
        EditedMediaItemSequence audioSeq=EditedMediaItemSequence.withAudioFrom(Collections.singletonList(audio));
        Composition composition=new Composition.Builder(videoSeq,audioSeq).build();

        File movies=getExternalFilesDir(Environment.DIRECTORY_MOVIES);if(movies==null)movies=getFilesDir();
        File dir=new File(movies,"PulseCanvas");if(!dir.exists())dir.mkdirs();
        String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date());
        File output=new File(dir,"Pulse-Canvas-"+width+"x"+height+"-"+exportFps+"fps-"+stamp+".mp4");

        transformer=new Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(new Transformer.Listener(){
                    @Override public void onCompleted(@NonNull Composition c,@NonNull ExportResult r){Uri saved=publishToGallery(output);transformer=null;workProgress.setVisibility(View.GONE);playBtn.setEnabled(true);refreshReady();statusLabel.setText("Visual terminado · "+width+"×"+height+" · "+exportFps+" FPS");toast(saved!=null?"Video guardado en la galería":"Video exportado: "+output.getName());}
                    @Override public void onError(@NonNull Composition c,@NonNull ExportResult r,@NonNull ExportException e){transformer=null;workProgress.setVisibility(View.GONE);playBtn.setEnabled(true);refreshReady();statusLabel.setText("Error al exportar");toast("No se pudo exportar: "+e.getMessage());}
                }).build();
        transformer.start(composition,output.getAbsolutePath());pollProgress();
    }

    private int[] exportSize(int pos){
        switch(pos){
            case 1:return new int[]{720,1280};
            case 2:return new int[]{1920,1080};
            case 3:return new int[]{1080,1920};
            default:return new int[]{1280,720};
        }
    }

    private void pollProgress(){if(transformer==null)return;ProgressHolder h=new ProgressHolder();int state=transformer.getProgress(h);if(state==Transformer.PROGRESS_STATE_AVAILABLE){workProgress.setProgress(h.progress);statusLabel.setText("Renderizando visualizador… "+h.progress+"%");}if(transformer!=null)handler.postDelayed(this::pollProgress,500);}

    private Uri publishToGallery(File source){
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.Q||!source.exists())return null;
        try{ContentValues v=new ContentValues();v.put(MediaStore.Video.Media.DISPLAY_NAME,source.getName());v.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");v.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/Pulse Canvas");v.put(MediaStore.Video.Media.IS_PENDING,1);ContentResolver r=getContentResolver();Uri u=r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v);if(u==null)return null;try(InputStream in=new FileInputStream(source);OutputStream out=r.openOutputStream(u)){if(out==null)return null;byte[] b=new byte[1024*1024];int n;while((n=in.read(b))>0)out.write(b,0,n);}v.clear();v.put(MediaStore.Video.Media.IS_PENDING,0);r.update(u,v,null,null);return u;}catch(Exception e){return null;}
    }

    private void persistReadPermission(Uri uri){try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}}
    private String queryDisplayName(Uri uri){try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0)return c.getString(i);}}catch(Exception ignored){}return "Archivo";}
    private String formatTime(long ms){long t=Math.max(0,ms)/1000;return String.format(Locale.US,"%02d:%02d",t/60,t%60);}
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private TextView text(String s,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);if(bold)v.setTypeface(v.getTypeface(),android.graphics.Typeface.BOLD);return v;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);return b;}
    private LinearLayout.LayoutParams weightLp(){return new LinearLayout.LayoutParams(0,dp(50),1f);}
    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}

    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(transformer!=null)transformer.cancel();if(player!=null)player.release();executor.shutdownNow();super.onDestroy();}
}
