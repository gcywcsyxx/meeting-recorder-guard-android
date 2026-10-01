package com.codex.meetingrecorder;

public final class ShortRecordingPolicyTest {
    static int checks;
    static void check(boolean value) { if(!value)throw new AssertionError("Check "+(checks+1));checks++; }
    static boolean shortClip(String row) {
        java.util.List<ShortRecordingPolicy.Item> items=ShortRecordingPolicy.parse(row);
        return !items.isEmpty() && items.get(0).isShort();
    }
    static String row(String duration,String folder,String pending,String trash) {
        return "Row: 0 _id=123, duration="+duration+", relative_path="+folder+", is_pending="+pending+", is_trashed="+trash;
    }
    public static void main(String[] args) {
        String folder=ShortRecordingPolicy.FOLDER;
        check(shortClip(row("4999",folder,"0","0")));
        check(shortClip(row("1",folder,"0","0")));
        check(!shortClip(row("5000",folder,"0","0")));
        check(!shortClip(row("5001",folder,"0","0")));
        check(!shortClip(row("0",folder,"0","0")));
        check(!shortClip(row("NULL",folder,"0","0")));
        check(!shortClip(row("-1",folder,"0","0")));
        check(!shortClip(row("2000",folder,"1","0")));
        check(!shortClip(row("2000",folder,"0","1")));
        check(!shortClip(row("2000","DCIM/Camera/","0","0")));
        check(!shortClip("ERROR: command timeout"));
        check(!shortClip("Row: 0 _id=123, duration=2000, relative_path="+folder));
        check(ShortRecordingPolicy.validQuery("EXIT=0\nNo result found."));
        check(ShortRecordingPolicy.validQuery("EXIT=0\n"+row("2000",folder,"0","0")));
        check(!ShortRecordingPolicy.validQuery("EXIT=0"));
        check(!ShortRecordingPolicy.validQuery("EXIT=0\nError while accessing provider:media"));
        System.out.println("PASS: "+checks+" short-recording safety checks");
    }
}
