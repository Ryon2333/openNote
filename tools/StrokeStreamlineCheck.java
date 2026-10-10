package cn.jiang.opennote;

/** Run without Android or JUnit: see docs/OPEN_SOURCE_INK.md. */
public final class StrokeStreamlineCheck {
    private static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    public static void main(String[] args){
        StrokeStreamline filter=new StrokeStreamline();
        Ink.Point first=new Ink.Point(2,3);first.pressure=.2f;first.t=10;first.tilt=.4f;
        Ink.Point result=filter.add(first);
        check(result!=first&&result.x==2&&result.y==3&&result.t==10&&result.pressure==.2f&&result.tilt==.4f,"first sample/metadata");
        result.x=2; // The raw input is a distinct object.
        Ink.Point second=new Ink.Point(12,13);second.pressure=.9f;
        result=filter.add(second);
        check(Math.abs(result.x-10.5)<1e-12&&Math.abs(result.y-11.5)<1e-12&&result.pressure==.9f&&second.x==12,"upstream interpolation recurrence/real pressure");
        System.out.println("PASS input immutability, recurrence and pressure metadata");

        filter=new StrokeStreamline();double rawVariation=0,filteredVariation=0,previousRaw=0,previousFiltered=0;
        for(int i=0;i<1000;i++){double y=i==0?0:(i%2==0?1:-1);result=filter.add(new Ink.Point(i,y));if(i>0){rawVariation+=Math.abs(y-previousRaw);filteredVariation+=Math.abs(result.y-previousFiltered);}previousRaw=y;previousFiltered=result.y;}
        check(filteredVariation<rawVariation*.8,"jitter reduction");
        System.out.println("PASS alternating jitter reduction ("+filteredVariation/rawVariation+")");

        filter=new StrokeStreamline();filter.add(new Ink.Point(0,0));result=filter.add(new Ink.Point(.01,.01));
        check(result.x>0&&result.x<.01,"short strokes must not be discarded");
        System.out.println("PASS subpixel short stroke retention");

        filter=new StrokeStreamline();StrokeStreamline replay=new StrokeStreamline();
        for(int i=0;i<10000;i++){Ink.Point raw=new Ink.Point(Math.sin(i*.2)*40,i*.05);raw.pressure=(i%100)/100f;Ink.Point live=filter.add(raw),saved=replay.add(raw);check(live.x==saved.x&&live.y==saved.y&&live.pressure==saved.pressure,"live/replay mismatch");}
        System.out.println("PASS 10000-sample incremental/replay equality");

        Ink ink=new Ink(0,2);ink.streamlined=true;ink.pressureSensitive=true;ink.points.add(new Ink.Point(10,50));ink.points.add(new Ink.Point(10,150));
        for(Ink part:Ink.insertSpace(java.util.Collections.singletonList(ink),100,50))check(part.streamlined&&part.pressureSensitive,"split loses brush mode");
        for(Ink part:Ink.removeSpace(java.util.Collections.singletonList(ink),80,20,false))check(part.streamlined&&part.pressureSensitive,"remove loses brush mode");
        System.out.println("PASS whitespace preserves rendering mode");
        System.out.println("5 checks passed");
    }
}
