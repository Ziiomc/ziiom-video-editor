package cl.ziiom.videoeditor.mobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

public final class VisualizerPainter {
    private VisualizerPainter() {}

    public static void drawOverlay(Canvas canvas, AudioAnalysis analysis, VisualizerConfig config,
                                   long timeMs, Bitmap centerImage) {
        if (analysis == null) return;
        float[] spectrum = analysis.spectrumAt(timeMs);
        if (spectrum.length == 0) return;

        float bass = clamp01(analysis.bassAt(timeMs) * config.sensitivity);
        float energy = clamp01(analysis.energyAt(timeMs) * config.sensitivity);
        int w = canvas.getWidth();
        int h = canvas.getHeight();

        Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
        int darkAlpha = (int)(205f * config.darkness * bass * bass);
        dark.setColor(Color.argb(Math.max(0, Math.min(215, darkAlpha)), 0, 0, 0));
        canvas.drawRect(0, 0, w, h, dark);

        drawBassAura(canvas, config, bass, energy);

        switch (config.mode) {
            case TRIANGLE:
                drawTriangle(canvas, spectrum, config, timeMs, bass, centerImage);
                break;
            case CIRCLE:
                drawCircle(canvas, spectrum, config, timeMs, bass, centerImage, false);
                break;
            case DIAMOND:
                drawDiamond(canvas, spectrum, config, timeMs, bass, centerImage);
                break;
            case RING:
                drawCircle(canvas, spectrum, config, timeMs, bass, centerImage, true);
                break;
            case WAVE:
                drawCenterArtwork(canvas, centerImage, config, bass, Shape.CIRCLE);
                drawWave(canvas, spectrum, config, timeMs);
                break;
            case MIRROR:
                drawCenterArtwork(canvas, centerImage, config, bass, Shape.ROUNDED);
                drawMirror(canvas, spectrum, config);
                break;
            case PARTICLES:
                drawCenterArtwork(canvas, centerImage, config, bass, Shape.CIRCLE);
                drawParticles(canvas, spectrum, config, timeMs, energy);
                break;
            case BARS:
            default:
                drawCenterArtwork(canvas, centerImage, config, bass, Shape.ROUNDED);
                drawBars(canvas, spectrum, config);
                break;
        }

        drawVignette(canvas, config);
    }

    private enum Shape { CIRCLE, ROUNDED }

    private static Paint paint(VisualizerConfig cfg, float width, int alpha, float colorPosition) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int color = paletteColor(cfg.palette, colorPosition);
        p.setColor(withAlpha(color, alpha));
        p.setStrokeWidth(width * cfg.thickness);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStyle(Paint.Style.STROKE);
        if (cfg.glow) {
            p.setShadowLayer(Math.max(3f, width * cfg.thickness * 3.1f), 0, 0,
                    withAlpha(color, Math.min(220, alpha)));
        }
        return p;
    }

    private static void drawBassAura(Canvas c, VisualizerConfig cfg, float bass, float energy) {
        if (bass < 0.08f) return;
        float min = Math.min(c.getWidth(), c.getHeight());
        float radius = min * (0.20f + 0.08f * bass);
        Paint aura = new Paint(Paint.ANTI_ALIAS_FLAG);
        int color = paletteColor(cfg.palette, 0.55f);
        aura.setStyle(Paint.Style.STROKE);
        aura.setStrokeWidth(min * 0.012f * Math.max(0.5f, cfg.thickness));
        aura.setColor(withAlpha(color, (int)(70f * bass * (0.5f + 0.5f * energy))));
        if (cfg.glow) aura.setShadowLayer(min * 0.035f, 0, 0, withAlpha(color, 120));
        c.drawCircle(c.getWidth()/2f, c.getHeight()/2f, radius, aura);
    }

    private static void drawTriangle(Canvas c, float[] s, VisualizerConfig cfg,
                                     long timeMs, float bass, Bitmap centerImage) {
        int w = c.getWidth(), h = c.getHeight();
        float cx = w / 2f, cy = h / 2f;
        float min = Math.min(w, h);
        float pulse = 1f + 0.05f * cfg.zoom * bass;
        float triH = min * cfg.centerSize * 1.08f * pulse;
        float triW = triH * 1.13f;

        float topY = cy - triH * 0.54f;
        float bottomY = cy + triH * 0.46f;
        float leftX = cx - triW / 2f;
        float rightX = cx + triW / 2f;

        float[] xs = {cx, rightX, leftX};
        float[] ys = {topY, bottomY, bottomY};
        Path clip = polygonPath(xs, ys);

        if (centerImage != null) {
            drawBitmapClipped(c, centerImage, new RectF(leftX, topY, rightX, bottomY), clip);
            Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
            shade.setColor(Color.argb(24, 0, 0, 0));
            c.drawPath(clip, shade);
        }

        c.drawPath(clip, paint(cfg, Math.max(2.8f, min * 0.006f), 220, 0.52f));
        drawSpectrumAroundPolygon(c, s, cfg, xs, ys, min * 0.18f);
        c.drawPath(clip, paint(cfg, Math.max(1.3f, min * 0.0025f), 105, 0.18f));
    }

    private static void drawDiamond(Canvas c, float[] s, VisualizerConfig cfg,
                                    long timeMs, float bass, Bitmap centerImage) {
        int w = c.getWidth(), h = c.getHeight();
        float cx = w / 2f, cy = h / 2f;
        float min = Math.min(w, h);
        float pulse = 1f + 0.045f * cfg.zoom * bass;
        float rx = min * cfg.centerSize * 0.46f * pulse;
        float ry = min * cfg.centerSize * 0.55f * pulse;

        float[] xs = {cx, cx + rx, cx, cx - rx};
        float[] ys = {cy - ry, cy, cy + ry, cy};
        Path clip = polygonPath(xs, ys);

        if (centerImage != null) {
            drawBitmapClipped(c, centerImage, new RectF(cx-rx, cy-ry, cx+rx, cy+ry), clip);
        }

        c.drawPath(clip, paint(cfg, Math.max(2.8f, min * 0.006f), 220, 0.52f));
        drawSpectrumAroundPolygon(c, s, cfg, xs, ys, min * 0.17f);
    }

    private static void drawCircle(Canvas c, float[] s, VisualizerConfig cfg,
                                   long timeMs, float bass, Bitmap centerImage, boolean ringMode) {
        int w = c.getWidth(), h = c.getHeight();
        float cx = w / 2f, cy = h / 2f;
        float min = Math.min(w, h);
        float pulse = 1f + 0.05f * cfg.zoom * bass;
        float r = min * cfg.centerSize * (ringMode ? 0.39f : 0.40f) * pulse;

        if (centerImage != null) {
            Path clip = new Path();
            clip.addCircle(cx, cy, r * 0.96f, Path.Direction.CW);
            drawBitmapClipped(c, centerImage, new RectF(cx-r, cy-r, cx+r, cy+r), clip);
        }

        float max = min * (ringMode ? 0.20f : 0.17f);
        float duration = Math.max(2200f, 12000f / Math.max(0.15f, cfg.rotationSpeed));
        float rot = ringMode ? (timeMs % (long)duration) / duration * 180f
                : (timeMs % (long)duration) / duration * 360f;

        for (int i = 0; i < s.length; i++) {
            float a = (float)Math.toRadians(rot + i * (360f / s.length));
            float v = clamp01(s[i] * cfg.sensitivity);
            float len = max * (0.06f + 0.94f * v);
            float inner = ringMode ? r - min * 0.010f : r;
            float x0 = cx + (float)Math.cos(a) * inner;
            float y0 = cy + (float)Math.sin(a) * inner;
            float x1 = cx + (float)Math.cos(a) * (r + len);
            float y1 = cy + (float)Math.sin(a) * (r + len);
            c.drawLine(x0, y0, x1, y1,
                    paint(cfg, Math.max(2.5f, min * 0.0055f), 242, i/(float)Math.max(1,s.length-1)));
        }

        c.drawCircle(cx, cy, r, paint(cfg, Math.max(1.8f, min * 0.0035f), 180, 0.5f));
        if (ringMode) {
            c.drawCircle(cx, cy, r * 1.07f + bass * min * 0.015f,
                    paint(cfg, Math.max(1.2f, min * 0.0022f), 90, 0.82f));
        }
    }

    private static Path polygonPath(float[] xs, float[] ys) {
        Path p = new Path();
        p.moveTo(xs[0], ys[0]);
        for (int i=1;i<xs.length;i++) p.lineTo(xs[i],ys[i]);
        p.close();
        return p;
    }

    private static void drawSpectrumAroundPolygon(Canvas c, float[] s, VisualizerConfig cfg,
                                                  float[] xs, float[] ys, float maxLen) {
        int n = xs.length;
        float cx = 0f, cy = 0f;
        for (int i=0;i<n;i++) { cx += xs[i]; cy += ys[i]; }
        cx /= n; cy /= n;

        float[] lengths = new float[n];
        float total = 0f;
        for (int i=0;i<n;i++) {
            int j=(i+1)%n;
            float dx=xs[j]-xs[i], dy=ys[j]-ys[i];
            lengths[i]=(float)Math.sqrt(dx*dx+dy*dy);
            total += lengths[i];
        }

        for (int b=0;b<s.length;b++) {
            float target=total*(b/(float)s.length);
            int edge=0;
            while(edge<n-1 && target>lengths[edge]) { target-=lengths[edge]; edge++; }
            int next=(edge+1)%n;
            float t=lengths[edge]<=0f?0f:target/lengths[edge];
            float x=xs[edge]+(xs[next]-xs[edge])*t;
            float y=ys[edge]+(ys[next]-ys[edge])*t;

            float tx=xs[next]-xs[edge], ty=ys[next]-ys[edge];
            float inv=1f/Math.max(0.001f,(float)Math.sqrt(tx*tx+ty*ty));
            float nx=-ty*inv, ny=tx*inv;
            float ox=x-cx, oy=y-cy;
            if(nx*ox+ny*oy<0f){nx=-nx;ny=-ny;}

            float v=clamp01(s[b]*cfg.sensitivity);
            float len=maxLen*(0.05f+0.95f*v);
            c.drawLine(x,y,x+nx*len,y+ny*len,
                    paint(cfg, Math.max(2.1f, Math.min(c.getWidth(),c.getHeight())*0.0047f),
                            245,b/(float)Math.max(1,s.length-1)));
        }
    }

    private static void drawCenterArtwork(Canvas c, Bitmap bitmap, VisualizerConfig cfg,
                                          float bass, Shape shape) {
        if (bitmap == null) return;
        int w=c.getWidth(), h=c.getHeight();
        float min=Math.min(w,h);
        float pulse=1f+0.04f*cfg.zoom*bass;
        float size=min*cfg.centerSize*0.62f*pulse;
        float cx=w/2f, cy=h*0.48f;
        RectF target=new RectF(cx-size/2f,cy-size/2f,cx+size/2f,cy+size/2f);

        Path clip=new Path();
        if(shape==Shape.CIRCLE) clip.addCircle(cx,cy,size/2f,Path.Direction.CW);
        else {
            float radius=size*0.11f;
            clip.addRoundRect(target,radius,radius,Path.Direction.CW);
        }
        drawBitmapClipped(c,bitmap,target,clip);
        c.drawPath(clip,paint(cfg,Math.max(1.8f,min*0.0032f),155,0.50f));
    }

    private static void drawBitmapClipped(Canvas c, Bitmap bitmap, RectF target, Path clip) {
        if(bitmap==null||bitmap.getWidth()<=0||bitmap.getHeight()<=0)return;
        int save=c.save();
        c.clipPath(clip);
        float scale=Math.max(target.width()/bitmap.getWidth(),target.height()/bitmap.getHeight());
        float dw=bitmap.getWidth()*scale, dh=bitmap.getHeight()*scale;
        RectF dst=new RectF(target.centerX()-dw/2f,target.centerY()-dh/2f,
                target.centerX()+dw/2f,target.centerY()+dh/2f);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(bitmap,null,dst,p);
        c.restoreToCount(save);
    }

    private static void drawBars(Canvas c,float[] s,VisualizerConfig cfg){
        int w=c.getWidth(),h=c.getHeight();
        float margin=w*0.07f,base=h*0.89f,usable=w-margin*2f,step=usable/s.length;
        for(int i=0;i<s.length;i++){
            float v=clamp01(s[i]*cfg.sensitivity);
            float bar=h*0.36f*(0.04f+0.96f*v);
            float x=margin+step*(i+0.5f);
            c.drawLine(x,base,x,base-bar,
                    paint(cfg,Math.max(2.2f,step*0.42f),245,i/(float)Math.max(1,s.length-1)));
        }
    }

    private static void drawMirror(Canvas c,float[] s,VisualizerConfig cfg){
        int w=c.getWidth(),h=c.getHeight();
        float center=h*0.70f,margin=w*0.06f,usable=w-margin*2f,step=usable/s.length;
        for(int i=0;i<s.length;i++){
            float v=clamp01(s[i]*cfg.sensitivity);
            float len=h*0.20f*v;
            float x=margin+step*(i+0.5f);
            c.drawLine(x,center-len,x,center+len,
                    paint(cfg,Math.max(2.2f,step*0.4f),235,i/(float)Math.max(1,s.length-1)));
        }
    }

    private static void drawWave(Canvas c,float[] s,VisualizerConfig cfg,long timeMs){
        int w=c.getWidth(),h=c.getHeight();
        float center=h*0.77f,amp=h*0.16f;
        for(int i=0;i<s.length-1;i++){
            float x0=i*(w/(float)(s.length-1));
            float x1=(i+1)*(w/(float)(s.length-1));
            float sign0=(i&1)==0?-1f:1f;
            float sign1=((i+1)&1)==0?-1f:1f;
            float phase=(float)Math.sin((timeMs/400.0)+(i*0.35))*0.12f;
            float y0=center+sign0*amp*clamp01(s[i]*cfg.sensitivity+phase);
            float y1=center+sign1*amp*clamp01(s[i+1]*cfg.sensitivity-phase);
            c.drawLine(x0,y0,x1,y1,
                    paint(cfg,Math.max(3f,h*0.0065f),245,i/(float)Math.max(1,s.length-2)));
        }
    }

    private static void drawParticles(Canvas c,float[] s,VisualizerConfig cfg,long timeMs,float energy){
        int w=c.getWidth(),h=c.getHeight();
        Random rnd=new Random(timeMs/55L);
        int count=52+(int)(energy*24f);
        for(int i=0;i<count;i++){
            float x=rnd.nextFloat()*w,y=rnd.nextFloat()*h;
            float band=s[i%s.length];
            float radius=(1.5f+10f*band*cfg.sensitivity)*(0.7f+energy);
            int alpha=55+(int)(190*clamp01(band*cfg.sensitivity));
            int color=paletteColor(cfg.palette,i/(float)Math.max(1,count-1));
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(withAlpha(color,Math.min(255,alpha)));
            if(cfg.glow)p.setShadowLayer(radius*1.8f,0,0,withAlpha(color,155));
            c.drawCircle(x,y,radius,p);
        }
    }

    private static void drawVignette(Canvas c,VisualizerConfig cfg){
        if(cfg.vignette<=0.01f)return;
        float cx=c.getWidth()/2f,cy=c.getHeight()/2f;
        float radius=(float)Math.hypot(cx,cy);
        int alpha=(int)(190f*clamp01(cfg.vignette));
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(cx,cy,radius,
                new int[]{Color.argb(0,0,0,0),Color.argb(alpha,0,0,0)},
                new float[]{0.50f,1f},Shader.TileMode.CLAMP));
        c.drawRect(0,0,c.getWidth(),c.getHeight(),p);
    }

    private static int paletteColor(VisualizerConfig.Palette palette,float t){
        t=clamp01(t);
        int a,b;
        switch(palette){
            case NEON: a=Color.rgb(0,255,210); b=Color.rgb(120,45,255); break;
            case PHONK: a=Color.rgb(255,60,205); b=Color.rgb(82,45,255); break;
            case FIRE: a=Color.rgb(255,225,75); b=Color.rgb(255,55,30); break;
            case GOLD: a=Color.rgb(255,245,190); b=Color.rgb(255,160,35); break;
            case WHITE: a=Color.rgb(255,255,255); b=Color.rgb(205,215,230); break;
            case ICE:
            default: a=Color.rgb(228,250,255); b=Color.rgb(55,165,255); break;
        }
        return lerpColor(a,b,t);
    }

    private static int lerpColor(int a,int b,float t){
        int r=(int)(Color.red(a)+(Color.red(b)-Color.red(a))*t);
        int g=(int)(Color.green(a)+(Color.green(b)-Color.green(a))*t);
        int bl=(int)(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t);
        return Color.rgb(r,g,bl);
    }

    private static int withAlpha(int color,int alpha){
        return Color.argb(Math.max(0,Math.min(255,alpha)),Color.red(color),Color.green(color),Color.blue(color));
    }

    public static RectF centerCropRect(int canvasW,int canvasH,int bitmapW,int bitmapH,float zoom){
        float scale=Math.max(canvasW/(float)bitmapW,canvasH/(float)bitmapH)*zoom;
        float dw=bitmapW*scale,dh=bitmapH*scale;
        float left=(canvasW-dw)/2f,top=(canvasH-dh)/2f;
        return new RectF(left,top,left+dw,top+dh);
    }

    private static float clamp01(float v){return Math.max(0f,Math.min(1f,v));}
}
