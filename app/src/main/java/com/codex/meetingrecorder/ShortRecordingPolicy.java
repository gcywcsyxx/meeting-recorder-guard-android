package com.codex.meetingrecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ShortRecordingPolicy {
    static final long MIN_DURATION_MS = 5000;
    static final String FOLDER = "DCIM/ScreenRecorder/";
    static boolean validQuery(String output) {
        return output.startsWith("EXIT=0") && !output.contains("Exception") && !output.contains("ERROR") && !output.contains("Error")
                && (output.contains("Row:") || output.contains("No result found"));
    }
    static final class Item {
        final long id, duration;
        final boolean pending, trashed;
        final String folder;
        Item(long id, long duration, boolean pending, boolean trashed, String folder) {
            this.id=id; this.duration=duration; this.pending=pending; this.trashed=trashed; this.folder=folder;
        }
        boolean isShort() {
            return id>0 && duration>0 && duration<MIN_DURATION_MS && !pending && !trashed && FOLDER.equals(folder);
        }
    }
    static List<Item> parse(String output) {
        List<Item> items=new ArrayList<>();
        for(String line:output.split("\\r?\\n")) {
            try {
                long id=Long.parseLong(value(line,"_id"));
                long duration=Long.parseLong(value(line,"duration"));
                String pending=value(line,"is_pending"), trashed=value(line,"is_trashed");
                // Missing metadata is not permission to delete.
                if(pending.isEmpty() || trashed.isEmpty()) continue;
                items.add(new Item(id,duration,!pending.equals("0"),!trashed.equals("0"),value(line,"relative_path")));
            } catch(NumberFormatException ignored) {}
        }
        return items;
    }
    private static String value(String line,String key) {
        Matcher m=Pattern.compile("(?:^|[ ,])"+Pattern.quote(key)+"=([^,]*)").matcher(line);
        return m.find()?m.group(1).trim():"";
    }
    private ShortRecordingPolicy() {}
}
