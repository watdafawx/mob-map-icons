package dev.watdafawx.mobmapicons.core;

import com.mojang.blaze3d.platform.NativeImage;
import dev.watdafawx.mobmapicons.api.IconStyle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;

/** Pure image operations; NativeImage pixels are ABGR, never ARGB. Inputs remain caller-owned. */
final class IconStyles {
    private IconStyles() {}

    static int category(Entity e) {
        String c = e.getType().getCategory().getName();
        if (e instanceof Enemy || c.equals("monster")) return rgb(214,48,40);
        if (e instanceof NeutralMob) return rgb(236,196,40);
        if (c.equals("water_creature") || c.equals("water_ambient") || c.equals("underground_water_creature") || c.equals("axolotls")) return rgb(50,120,224);
        return c.equals("ambient") ? rgb(140,140,150) : rgb(64,176,64);
    }
    private static int rgb(int r,int g,int b) { return 0xff000000 | b<<16 | g<<8 | r; }
    private static int lum(int c) { return ((c&255)*299 + ((c>>8)&255)*587 + ((c>>16)&255)*114)/1000; }

    static NativeImage apply(IconStyle style, NativeImage source, NativeImage head, Entity entity) {
        int n=source.getWidth(), col=category(entity);
        NativeImage out=new NativeImage(n,n,true);
        if (style==IconStyle.BADGE || style==IconStyle.HEAD_PIN) {
            boolean pin=style==IconStyle.HEAD_PIN;
            double radius=n*(pin?.40:.50), cy=pin?radius+.5:n/2.0, cx=n/2.0;
            double ring=Math.max(2,Math.round(n*.11));
            // Supersample geometry, retaining smooth transparent edges.
            for(int y=0;y<n;y++) for(int x=0;x<n;x++) {
                int count=0,inner=0;
                for(int sy=0;sy<8;sy++) for(int sx=0;sx<8;sx++) {
                    double dx=x+(sx+.5)/8-cx, dy=y+(sy+.5)/8-cy;
                    double d=Math.hypot(dx,dy);
                    boolean tail=pin && dy>=radius*.6 && y+(sy+.5)/8<n-.5 && Math.abs(dx)<radius*.8*(n-.5-(y+(sy+.5)/8))/(n-.5-(cy+radius*.6));
                    if(d<=radius || tail) count++;
                    if(d<=radius-ring) inner++;
                }
                if(count>0) {
                    int c=0;
                    for(int shift=0;shift<24;shift+=8) c|=((((col>>shift)&255)*(count-inner)+((rgb(226,222,208)>>shift)&255)*inner)/count)<<shift;
                    out.setPixelRGBA(x,y,(count*255/64)<<24|c);
                }
            }
            fit(pin && head!=null?head:source,out,(int)(2*(radius-ring)-3),(int)Math.round(cy-n/2.0),0,1);
        } else if(style==IconStyle.CHIBI && head!=null) {
            // Mock "8 chibi": lower 65% of the body shrunk to ~46% of the icon and bottom-aligned, then
            // the head (66% tall) sitting on it, overlapping the body's top by 18% of the head height.
            int[] body=fit(source,out,(int)(n*.60),0,.35,.77,1,n);
            fit(head,out,(int)(n*.84),0,0,.66/.84,2,body==null?n:body[1]);
            outline(out,rgb(24,18,20));
        } else if(style==IconStyle.RETRO) {
            try(NativeImage small=new NativeImage(16,16,true)) {
                fit(source,small,14,0,0,1);
                for(int y=0;y<16;y++) for(int x=0;x<16;x++) {
                    int c=small.getPixelRGBA(x,y), v=0;
                    if((c>>>24)>110) {
                        int l=lum(c);
                        for(int sh=0;sh<24;sh+=8) { int z=(c>>sh)&255; z=Math.max(0,Math.min(255,(int)((l+(z-l)*1.3-128)*1.25+128))); v|=z<<sh; }
                        v|=0xff000000;
                    }
                    small.setPixelRGBA(x,y,v);
                }
                quantize(small);
                outline(small,rgb(24,18,20));
                for(int y=0;y<n;y++) for(int x=0;x<n;x++) out.setPixelRGBA(x,y,small.getPixelRGBA(x*16/n,y*16/n));
            }
        } else {
            fit(source,out,n-(style==IconStyle.PLAIN||style==IconStyle.TOP_DOWN?0:style==IconStyle.SHADOW?3:2),0,0,1);
            if(style==IconStyle.SILHOUETTE || style==IconStyle.PAPER_INK) {
                int lo=0,hi=255,total=0;int[] histogram=new int[256];
                for(int y=0;y<n;y++) for(int x=0;x<n;x++) {int c=out.getPixelRGBA(x,y);if((c>>>24)>100){histogram[lum(c)]++;total++;}}
                int cutoff=total*(style==IconStyle.SILHOUETTE?3:2)/100,count=0;
                while(lo<255 && count+histogram[lo]<=cutoff) count+=histogram[lo++];
                count=0;while(hi>lo && count+histogram[hi]<=cutoff) count+=histogram[hi--];
                for(int y=0;y<n;y++) for(int x=0;x<n;x++) {
                    int c=out.getPixelRGBA(x,y),l=Math.max(0,Math.min(255,(lum(c)-lo)*255/Math.max(1,hi-lo))),v=0;
                    if((c>>>24)>100) {
                        if(style==IconStyle.SILHOUETTE) {for(int sh=0;sh<24;sh+=8) v|=(((col>>sh)&255)*(150+l*105/255)/255)<<sh; v|=0xff000000;}
                        else {l=Math.round(l/51f)*51;v=rgb(44+170*l/255,28+162*l/255,16+124*l/255);}
                    }
                    out.setPixelRGBA(x,y,v);
                }
            }
            if(style==IconStyle.OUTLINE || style==IconStyle.SILHOUETTE || style==IconStyle.PAPER_INK) outline(out,style==IconStyle.SILHOUETTE?shade(col,.45):style==IconStyle.PAPER_INK?rgb(40,24,14):rgb(24,18,20));
            if(style==IconStyle.SHADOW) {
                int[] pixels=pixels(out);int off=Math.max(1,Math.round(n/14f)),r=Math.max(1,Math.round(n/22f));
                for(int y=0;y<n;y++) for(int x=0;x<n;x++) {
                    int sum=0;
                    for(int dy=-r;dy<=r;dy++) for(int dx=-r;dx<=r;dx++) {int xx=x-off+dx,yy=y-off+dy;if(xx>=0&&yy>=0&&xx<n&&yy<n&&(pixels[yy*n+xx]>>>24)>100)sum+=150;}
                    out.setPixelRGBA(x,y,over(pixels[y*n+x],(sum/((2*r+1)*(2*r+1)))<<24));
                }
            }
        }
        return out;
    }
    /** Median-cut palette, at most twelve colours; no dithering. */
    private static void quantize(NativeImage image) {
        java.util.List<java.util.List<Integer>> boxes=new java.util.ArrayList<>();
        java.util.List<Integer> colors=new java.util.ArrayList<>();
        for(int c:pixels(image)) if((c>>>24)>0) colors.add(c);
        if(colors.isEmpty())return;
        boxes.add(colors);
        while(boxes.size()<12) {
            int best=-1,channel=0,range=-1;
            for(int i=0;i<boxes.size();i++) {
                if(boxes.get(i).size()<2)continue;
                for(int sh=0;sh<24;sh+=8) {
                    int lo=255,hi=0;
                    for(int c:boxes.get(i)){int v=(c>>sh)&255;lo=Math.min(lo,v);hi=Math.max(hi,v);}
                    if(hi-lo>range){range=hi-lo;best=i;channel=sh;}
                }
            }
            if(best<0||range==0)break;
            var box=boxes.remove(best);final int shift=channel;
            box.sort(java.util.Comparator.comparingInt(c -> (c>>shift)&255));
            int mid=box.size()/2;
            boxes.add(new java.util.ArrayList<>(box.subList(0,mid)));
            boxes.add(new java.util.ArrayList<>(box.subList(mid,box.size())));
        }
        int[] palette=new int[boxes.size()];
        for(int i=0;i<palette.length;i++) {
            int r=0,g=0,b=0;for(int c:boxes.get(i)){r+=c&255;g+=(c>>8)&255;b+=(c>>16)&255;}
            int n=boxes.get(i).size();palette[i]=rgb(r/n,g/n,b/n);
        }
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
            int c=image.getPixelRGBA(x,y);if((c>>>24)==0)continue;
            int nearest=palette[0],distance=Integer.MAX_VALUE;
            for(int p:palette) {int d=0;for(int sh=0;sh<24;sh+=8){int delta=((p>>sh)&255)-((c>>sh)&255);d+=delta*delta;}if(d<distance){distance=d;nearest=p;}}
            image.setPixelRGBA(x,y,nearest);
        }
    }

    private static int shade(int c,double f) { return rgb((int)((c&255)*f),(int)(((c>>8)&255)*f),(int)(((c>>16)&255)*f)); }
    private static int[] pixels(NativeImage im) {int n=im.getWidth();int[] p=new int[n*n];for(int y=0;y<n;y++)for(int x=0;x<n;x++)p[y*n+x]=im.getPixelRGBA(x,y);return p;}
    private static void outline(NativeImage im,int color) {
        int n=im.getWidth();int[] p=pixels(im);
        for(int y=0;y<n;y++)for(int x=0;x<n;x++) {
            if((p[y*n+x]>>>24)>100)continue;
            boolean edge=false;
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int xx=x+dx,yy=y+dy;if(xx>=0&&yy>=0&&xx<n&&yy<n&&(p[yy*n+xx]>>>24)>100)edge=true;}
            if(edge)im.setPixelRGBA(x,y,over(p[y*n+x],color));
        }
    }
    private static int over(int src,int dst) {
        int a=src>>>24,b=dst>>>24,alpha=a+b*(255-a)/255;if(alpha==0)return 0;
        int c=alpha<<24;for(int s=0;s<24;s+=8)c|=((((src>>s)&255)*a+((dst>>s)&255)*b*(255-a)/255)/alpha)<<s;return c;
    }
    private static void fit(NativeImage src,NativeImage dst,int box,int offset,double crop,double heightScale) {
        fit(src,dst,box,offset,crop,heightScale,0,0);
    }
    /**
     * Scales the opaque part of src (minus the top {@code crop} share) into dst. anchor 0: centred, shifted
     * by offset. 1: bottom edge at anchorY. 2: bottom edge at anchorY+18% of its height, top clamped to 0.
     * @return {x, y, w, h} as placed, or null when src is empty
     */
    private static int[] fit(NativeImage src,NativeImage dst,int box,int offset,double crop,double heightScale,int anchor,int anchorY) {
        int x0=src.getWidth(),y0=src.getHeight(),x1=-1,y1=-1;
        for(int y=0;y<src.getHeight();y++)for(int x=0;x<src.getWidth();x++)if((src.getPixelRGBA(x,y)>>>24)>8){x0=Math.min(x0,x);y0=Math.min(y0,y);x1=Math.max(x1,x);y1=Math.max(y1,y);}
        if(x1<0)return null;
        if(crop>0) {
            y0+=(int)((y1-y0+1)*crop);
            // Re-measure the width of what is left, so a cropped-off head or wings do not shrink the body.
            int nx0=src.getWidth(),nx1=-1;
            for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++)if((src.getPixelRGBA(x,y)>>>24)>8){nx0=Math.min(nx0,x);nx1=Math.max(nx1,x);}
            if(nx1<0)return null;
            x0=nx0;x1=nx1;
        }
        double scale=Math.min((double)Math.max(1,box)/(x1-x0+1),(double)Math.max(1,box)*heightScale/(y1-y0+1));
        int w=Math.max(1,(int)Math.round((x1-x0+1)*scale)),h=Math.max(1,(int)Math.round((y1-y0+1)*scale)),ox=(dst.getWidth()-w)/2,oy=(dst.getHeight()-h)/2+offset;
        if(anchor==1) oy=anchorY-h;
        else if(anchor==2) oy=Math.max(0,anchorY-(int)Math.round(h*.82));
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            if(ox+x<0||oy+y<0||ox+x>=dst.getWidth()||oy+y>=dst.getHeight())continue;
            int c;
            if(scale>=1) c=src.getPixelRGBA(Math.min(x1,x0+x*(x1-x0+1)/w),Math.min(y1,y0+y*(y1-y0+1)/h));
            else {
                int ax=x0+x*(x1-x0+1)/w,bx=x0+(x+1)*(x1-x0+1)/w;
                int ay=y0+y*(y1-y0+1)/h,by=y0+(y+1)*(y1-y0+1)/h;
                long alpha=0,r=0,g=0,b=0;int count=0;
                for(int yy=ay;yy<by;yy++)for(int xx=ax;xx<bx;xx++) {int pixel=src.getPixelRGBA(xx,yy),a=pixel>>>24;alpha+=a;r+=(pixel&255)*a;g+=((pixel>>8)&255)*a;b+=((pixel>>16)&255)*a;count++;}
                c=alpha==0?0:((int)(alpha/Math.max(1,count))<<24)|((int)(b/alpha)<<16)|((int)(g/alpha)<<8)|(int)(r/alpha);
            }
            dst.setPixelRGBA(ox+x,oy+y,over(c,dst.getPixelRGBA(ox+x,oy+y)));
        }
        return new int[]{ox,oy,w,h};
    }
}
