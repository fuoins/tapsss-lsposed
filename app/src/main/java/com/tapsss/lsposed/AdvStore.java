package com.tapsss.lsposed;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 高级模式的本地历史记录（只存在本机，不上传）。
 *
 * 每完成一局写一条，供成绩弹窗的「今日统计」、本地页的「高级模式」入口、
 * 排名页的「高级模式」本地排名共用。存储为文本行，字段用逗号分隔：
 * mode,type,blind,n,x,time,reaction,errors,timestamp
 */
public final class AdvStore {

    private static final String KEY = "adv_history_v1";
    private static final int MAX_RECORDS = 3000;

    public static final class Rec {
        public int mode;
        public int type;
        public int n;
        public int x;
        public int errors;
        public boolean blind;
        public long time;
        public long reaction;
        public long ts;

        /** 「简单-爆手速」这类标签，和成绩面板一致 */
        public String label() {
            String base;
            if (type == 2) base = "简单打乱";
            else if (type == 3) base = "打乱";
            else if (blind) base = (type == 1 ? "简单盲玩" : "盲玩");
            else base = (type == 1 ? "简单" : "普通");
            if (mode <= 0 || mode >= AdvancedSchulte.SHORT.length) return base;
            return base + "-" + AdvancedSchulte.SHORT[mode];
        }

        public String levelText() {
            return n + "×" + n;
        }
    }

    private AdvStore() {
    }

    public static synchronized void add(Context c, int mode, int type, boolean blind,
                                        int n, int x, long time, long reaction, int errors) {
        if (c == null || mode <= 0) return;
        try {
            SharedPreferences p = Prefs.get(c);
            if (p == null) return;
            String old = p.getString(KEY, "");
            StringBuilder sb = new StringBuilder(old == null ? "" : old);
            if (sb.length() > 0) sb.append('\n');
            sb.append(mode).append(',').append(type).append(',').append(blind ? 1 : 0)
                    .append(',').append(n).append(',').append(x)
                    .append(',').append(Math.max(0L, time))
                    .append(',').append(Math.max(0L, reaction))
                    .append(',').append(errors)
                    .append(',').append(System.currentTimeMillis());
            p.edit().putString(KEY, trim(sb.toString())).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 记录太多时只保留最近的 MAX_RECORDS 条 */
    private static String trim(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        if (count + 1 <= MAX_RECORDS) return text;
        int skip = count + 1 - MAX_RECORDS;
        int idx = -1;
        for (int i = 0; i < skip; i++) {
            idx = text.indexOf('\n', idx + 1);
            if (idx < 0) break;
        }
        return idx >= 0 && idx + 1 < text.length() ? text.substring(idx + 1) : text;
    }

    public static List<Rec> all(Context c) {
        ArrayList<Rec> out = new ArrayList<>();
        try {
            SharedPreferences p = Prefs.get(c);
            if (p == null) return out;
            String text = p.getString(KEY, "");
            if (text == null || text.isEmpty()) return out;
            for (String line : text.split("\n")) {
                Rec r = parse(line);
                if (r != null) out.add(r);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** n <= 0 表示不限阶数；SPEED 模式下 x > 0 时按目标点击数过滤 */
    public static List<Rec> query(Context c, int type, boolean blind, int mode, int n, int x) {
        ArrayList<Rec> out = new ArrayList<>();
        if (mode <= 0) return out;
        for (Rec r : all(c)) {
            if (r.mode != mode) continue;
            if (r.type != type) continue;
            if (r.blind != blind) continue;
            if (n > 0 && r.n != n) continue;
            if (mode == AdvancedSchulte.SPEED && x > 0 && r.x != x) continue;
            out.add(r);
        }
        return out;
    }

    public static List<Rec> today(Context c, int type, boolean blind, int mode, int n, int x) {
        long start = todayStart();
        ArrayList<Rec> out = new ArrayList<>();
        for (Rec r : query(c, type, blind, mode, n, x)) {
            if (r.ts >= start) out.add(r);
        }
        return out;
    }

    public static long todayStart() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** 按用时从快到慢（排名用） */
    public static void sortByTime(List<Rec> list) {
        Collections.sort(list, new Comparator<Rec>() {
            @Override
            public int compare(Rec a, Rec b) {
                return Long.compare(a.time, b.time);
            }
        });
    }

    /** 按时间从新到旧（记录页用） */
    public static void sortByNewest(List<Rec> list) {
        Collections.sort(list, new Comparator<Rec>() {
            @Override
            public int compare(Rec a, Rec b) {
                return Long.compare(b.ts, a.ts);
            }
        });
    }

    private static Rec parse(String line) {
        if (line == null || line.isEmpty()) return null;
        String[] p = line.split(",");
        if (p.length < 9) return null;
        try {
            Rec r = new Rec();
            r.mode = Integer.parseInt(p[0]);
            r.type = Integer.parseInt(p[1]);
            r.blind = Integer.parseInt(p[2]) != 0;
            r.n = Integer.parseInt(p[3]);
            r.x = Integer.parseInt(p[4]);
            r.time = Long.parseLong(p[5]);
            r.reaction = Long.parseLong(p[6]);
            r.errors = Integer.parseInt(p[7]);
            r.ts = Long.parseLong(p[8]);
            return r.mode > 0 && r.n > 0 ? r : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
