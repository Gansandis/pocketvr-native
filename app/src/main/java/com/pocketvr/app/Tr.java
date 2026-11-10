package com.pocketvr.app;

import java.util.HashMap;

/**
 * Words on screen. The app's own texts are written in Thai in the code; with the language set to English (the start-up language) they are turned into English here,
 * right before they are drawn. Pictures made of emoji are removed from every text (the app shows words only).
 */
public final class Tr {
    private Tr() { }

    /** false = English (start-up), true = Thai */
    public static volatile boolean thai = false;
    private static final HashMap<String, String> cache = new HashMap<>();
    private static final String[][] PAIRS = {
        {"ปัดนิ้วฟันมอนสเตอร์ที่เดินเข้าหาคุณ", "Swipe your finger to slash the monsters coming for you"},
        {"ปัดนิ้วฟันผลไม้ให้ขาด", "Swipe your finger to slice the fruit"},
        {"อย่าฟันระเบิด · ผลไม้ตก 3 ลูก = จบเกม", "Avoid the bombs · 3 fruits dropped = game over"},
        {"มอนสเตอร์ 3D ในห้อง", "3D monsters in your room"},
        {"ฟันมอนสเตอร์ 3D", "Monster slayer 3D"},
        {"ปัดนิ้วฟันผลไม้", "Swipe to slice fruit"},
        {"เกมฟันดาบ 3D", "Sword game 3D"},
        {"เกมฟันผลไม้", "Fruit slicing game"},
        {"คะแนนสูงสุด", "Best"},
        {" ลูกพร้อมกัน! +", " at once! +"},
        {"ด่านบอส! รอบที่ ", "Boss wave "},
        {"ฟันผลไม้", "Fruit slicing"},
        {"เริ่มต่อสู้", "Fight"},
        {"สู้อีกครั้ง", "Fight again"},
        {"เล่นอีกครั้ง", "Play again"},
        {"ฉาก: เปิด", "Scene: on"},
        {"ฉาก: ปิด", "Scene: off"},
        {"รอบที่ ", "Wave "},
        {"คอมโบ x", "Combo x"},
        {"เริ่มเล่น", "Play"},
        {"พ่ายแพ้", "Defeated"},
        {"จบเกม", "Game over"},
        {"คะแนน", "Score"},
        {"แอปนี้ไม่ยอมอยู่ในหน้าต่างลอย (เด้งไปจอจริง)\nใช้แบบลอยไม่ได้ ลองแอปอื่น หรือกด ⟳", "This app will not stay in a floating window (it jumps to the real screen).\nTry another app, or press refresh"},
        {"\nถ้ายังไม่ขึ้น: แอปนี้อาจไม่ยอมเปิดบนจอเสมือน หรือเด้งไปจอจริง", "\nIf nothing shows: this app may refuse to open on the virtual screen or jump to the real one"},
        {"เมนูบนฝ่ามือซ้าย (แบมือซ้าย จิ้มไอคอนด้วยนิ้วมือขวา)", "Left palm menu (open left palm, poke with right finger)"},
        {"จำลองการหมุนรอบคอ (ให้หน้าต่างมีมิติลึกตอนหันหัว)", "Neck pivot (adds depth when you turn your head)"},
        {"ตรวจเฉพาะมือผู้ใช้ (ตัดมือคนอื่นที่อยู่ไกลออกไป)", "Only the user's hands (ignore far-away hands)"},
        {"กดอนุญาต PocketVR ในหน้าต่าง Shizuku แล้วลองใหม่", "Allow PocketVR in the Shizuku window, then try again"},
        {"ความหน่วงของภาพกล้อง (ปรับให้ภาพนิ่งตอนหันหัว)", "Camera delay (tune so the view stays still when turning)"},
        {"เครื่องอุ่น: ลดความเร็วเพื่อประหยัดความร้อน", "Device is warm: slowing down to save heat"},
        {"จีบนิ้วโป้ง+นิ้วกลางค้างไว้ = เปิด/ปิดเมนู", "Hold thumb + middle finger = open / close the menu"},
        {"ตามปลายนิ้วต่อระหว่างโมเดล (ท่าจีบไวขึ้น)", "Follow fingertips between results (faster pinch)"},
        {"ไม่ทำงาน (เปิดแอป Shizuku แล้วกดเริ่ม)", "Not running (open the Shizuku app and press Start)"},
        {"ปรับความละเอียดภาพอัตโนมัติ (ให้ลื่น)", "Auto picture resolution (smoother)"},
        {"เว็บแอปยอดนิยม (เปิดเป็นหน้าต่างลอย)", "Popular web apps (open as floating windows)"},
        {"จิ้มเพื่อพิมพ์ที่อยู่เว็บหรือคำค้นหา", "Tap to type a web address or search"},
        {"เปิดเบราว์เซอร์ได้สูงสุด 3 หน้าต่าง", "Up to 3 browser windows"},
        {"2 ข้าง (เปลี่ยนเป็น 1 เองถ้าไม่ใช้)", "Two hands"},
        {"แก้ขอบสีแดง/ฟ้า (สีเหลื่อมจากเลนส์)", "Fix red / blue edges (lens colour fringing)"},
        {"ชดเชยภาพกล้องตามการหันหัว (ลดหน่วง)", "Camera timewarp (less lag when turning)"},
        {"มุมมองต่อตา (ให้ตรงกับเลนส์แว่น)", "Field of view per eye (match your lenses)"},
        {"ตามมือต่อระหว่างโมเดล (ลดหน่วง)", "Follow hand between model results (less lag)"},
        {"พิมพ์ที่ช่องของหน้าต่างที่เลือก", "Types into the selected window"},
        {"เปิดแอปลอยได้สูงสุด 2 หน้าต่าง", "Up to 2 floating apps"},
        {"เข็มทิศช่วยแก้ทิศ (กันจอไหล)", "Compass helps heading (stops drift)"},
        {"ตัวช่วยสัมผัสเร็ว: เริ่มใหม่", "Fast touch helper: restart"},
        {"ยังไม่ได้รับอนุญาตให้อ่านรูป", "Permission to read photos was not given"},
        {"ถอดแว่นแล้วกดอนุญาตบนหน้าจอ", "Take the headset off and tap Allow on the screen"},
        {"แก้ภาพซ้อน (ขยับเข้าหาจมูก)", "Fix double image (move towards the nose)"},
        {"Shizuku (เปิดแอปจริงแบบลอย)", "Shizuku (run real apps in floating windows)"},
        {"จัดหน้าต่างและขนาดใหม่แล้ว", "Windows and sizes reset"},
        {"ความร้อน / ประหยัดพลังงาน", "Heat / power saving"},
        {"ยังไม่ได้เปิดเบราว์เซอร์", "No browser open"},
        {"คาดการณ์มือ (ตามมือทัน)", "Hand prediction (keeps up with your hand)"},
        {"เปิดเบราว์เซอร์ไม่ได้: ", "Cannot open browser: "},
        {"ปิดอยู่ (เห็นห้องจริง)", "Off (real room visible)"},
        {"ควบคุม แสดงผล VR ทดลอง", "Controls, display, VR, experimental"},
        {"การ์ดจอ GPU (ค่าเดิม)", "GPU (default)"},
        {"มือหลัก (ตอนยกสองมือ)", "Main hand (when both are up)"},
        {"เลนส์ (ชดเชยความโค้ง)", "Lens (curve correction)"},
        {"ขนาดห้อง (ความไวเดิน)", "Room size (walking sensitivity)"},
        {"กลับเป็นความเร็วปกติ", "Back to normal speed"},
        {"จัดหน้าต่างมาตรงหน้า", "Bring windows in front"},
        {"คืนค่าตั้งต้นทั้งหมด", "Reset all settings"},
        {"เวอร์ชันเว็บ (สำรอง)", "Web version (backup)"},
        {"จีบที่ปุ่มเพื่อพิมพ์", "Pinch a key to type"},
        {"รอสิทธิ์ (กดเพื่อขอ)", "Waiting for permission (tap to ask)"},
        {"ยังไม่ได้เลือกวิดีโอ", "No video selected"},
        {"เปิดหน้าต่างลอยแล้ว", "Floating window opened"},
        {"Shizuku ยังไม่พร้อม", "Shizuku is not ready"},
        {"ความละเอียดตรวจมือ", "Hand detection resolution"},
        {" (กำลังลดความเร็ว)", " (slowing down)"},
        {"ตัวประมวลผลตรวจมือ", "Hand detection processor"},
        {"CPU (ลองถ้าภาพแลค)", "CPU (try if laggy)"},
        {"ไม่พบไฟล์ในเครื่อง", "No files found on this device"},
        {"กำลังเปิดวิดีโอ...", "Opening video..."},
        {"ประหยัดมาก 30 fps", "Saver 30 fps"},
        {"เปิด %.1f,%.1f ม.", "On %.1f, %.1f m"},
        {"คืนค่าตั้งต้นแล้ว", "Settings reset"},
        {"เครื่องมือและระบบ", "Tools and system"},
        {"ห้องมืด เห็นแค่จอ", "Dark room, screens only"},
        {"นาฬิกา / จับเวลา", "Clock / Stopwatch"},
        {"ข้างเดียว (เร็ว)", "One hand (fast)"},
        {"ปรับเทียบการเดิน", "Calibrate walking"},
        {"เดิน 1 ม. แล้วกด", "Walk 1 m, then press"},
        {"เปิดหน้าเว็บเดิม", "Open the old web page"},
        {"แกลเลอรี/โรงหนัง", "Gallery / Cinema"},
        {"ยังไม่ได้เปิดแอป", "No app open"},
        {"ตัวช่วยหยุดทำงาน", "Helper stopped"},
        {"ไม่มีข้อผิดพลาด", "No errors"},
        {"จำนวนมือที่ตรวจ", "Hands to detect"},
        {"หน้าต่างลอยเบาๆ", "Gently floating windows"},
        {"เส้นขอบเรืองแสง", "Glowing outline"},
        {"กำลังดูภาพ 360°", "Viewing 360 photo"},
        {"กำลังเล่นวิดีโอ", "Playing video"},
        {"รูป วิดีโอ 360°", "Photos, videos, 360"},
        {"กำลังเปิดรูป...", "Opening photo..."},
        {"คาดการณ์นำหน้า", "Prediction lead"},
        {"นาฬิกา/จับเวลา", "Clock / Stopwatch"},
        {"กล้อง %.0f fps", "Camera %.0f fps"},
        {"ประหยัด 45 fps", "Saver 45 fps"},
        {"ความคมภาพกล้อง", "Camera sharpness"},
        {"วางเมนูตรงหน้า", "Put the menu in front of you"},
        {"ประมาณ %.1f ม.", "About %.1f m"},
        {"วัดได้ %.2f ม.", "Measured %.2f m"},
        {"ปิด (เร็วสุด)", "Off (fastest)"},
        {"ความทึบของแผง", "Panel opacity"},
        {"แยกจอซ้าย-ขวา", "Split screen (left / right)"},
        {"ทิศและตำแหน่ง", "Direction and position"},
        {"หน่วงภาพกล้อง", "Camera delay"},
        {"ใช้เวลาจากภาพ", "Use picture time"},
        {"กำลังเปิดแอป…", "Opening app…"},
        {"เปิดรูปไม่ได้", "Cannot open photo"},
        {"ยังไม่ได้โหลด", "Not loaded"},
        {"ปิด PocketVR", "Quit PocketVR"},
        {"หมุนไม่ได้: ", "Cannot rotate: "},
        {"เปิดไม่ได้: ", "Cannot open: "},
        {"กำลังโหลด...", "Loading..."},
        {"สัมผัสเร็ว ✓", "Fast touch on"},
        {"แอปในเครื่อง", "Apps on this device"},
        {"จัดกลางใหม่", "Recenter"},
        {"เบราว์เซอร์", "Browser"},
        {"โหมดโรงหนัง", "Cinema mode"},
        {"ตั้งค่าแล้ว", "Done"},
        {" (ประหยัด ", " (saving "},
        {"กำลังเปิด ", "Opening "},
        {" (แม่นสุด)", " (most accurate)"},
        {"อัตโนมัติ ", "Auto "},
        {"เคอร์เซอร์", "Cursor"},
        {"ตัวเลขบนจอ", "On-screen numbers"},
        {"เดินรอบได้", "Walk around"},
        {"เครื่องมือ", "Tools"},
        {"เปิดนอก VR", "Open outside VR"},
        {"กำลังโหลด…", "Loading…"},
        {"คัดลอกแล้ว", "Copied"},
        {"สถานะระบบ", "System status"},
        {"อัตโนมัติ", "Auto"},
        {"ผิดพลาด: ", "Error: "},
        {"ออกจากแอป", "Quit"},
        {" หน้าต่าง", " windows"},
        {"คีย์บอร์ด", "Keyboard"},
        {"สัมผัสช้า", "Slow touch"},
        {"ครั้ง/วิ", "/s"},
        {"เปิดอยู่", "On"},
        {"↺ รีเซ็ต", "Reset"},
        {"เส้นโครง", "Skeleton lines"},
        {"คาดการณ์", "Predict"},
        {"เว้นวรรค", "Space"},
        {"ออก 360°", "Exit 360"},
        {"พร้อมใช้", "Ready"},
        {"ตรวจมือ", "Hand detect"},
        {"แปลงภาพ", "Convert"},
        {"ปิดอยู่", "Off"},
        {"▶ เริ่ม", "Start"},
        {" (เร็ว)", " (fast)"},
        {"มือซ้าย", "Left hand"},
        {"เล็กจาง", "Small, faint"},
        {"โครงมือ", "Hand skeleton"},
        {"โหมด VR", "VR mode"},
        {"ผิดพลาด", "Error"},
        {"เว็บแอป", "Web apps"},
        {"เปิดลอย", "Open floating"},
        {"ตั้งค่า", "Settings"},
        {"เวลาภาพ", "Picture time"},
        {"หน้าแรก", "Home"},
        {"นำหน้า", "Lead"},
        {"ตามมือ", "Track"},
        {"⏸ หยุด", "Pause"},
        {"แผนที่", "Maps"},
        {"ควบคุม", "Controls"},
        {"มือขวา", "Right hand"},
        {"แสดงผล", "Display"},
        {"รีเซ็ต", "Reset"},
        {"วิดีโอ", "Videos"},
        {"ล่าสุด", "Latest"},
        {"กล้อง", "Camera"},
        {"โมเดล", "Model"},
        {"ทดลอง", "Experimental"},
        {"แยกจอ", "Split"},
        {"เลนส์", "Lens"},
        {"หน่วง", "Delay"},
        {"ตรวจ", "Detect"},
        {"แปลง", "Convert"},
        {"เว้น", "Gap"},
        {"เปิด", "On"},
        {"กลาง", "Medium"},
        {"ใหญ่", "Large"},
        {"ระบบ", "System"},
        {"เมนู", "Menu"},
        {"ภาษา", "Language"},
        {"วาด", "Draw"},
        {"ภาพ", "Scene"},
        {"ปิด", "Off"},
        {"เบา", "Light"},
        {"แรง", "Strong"},
        {"ออก", "Quit"},
        {"แอป", "Apps"},
        {"ไทย", "TH"},
        {"รูป", "Photos"},
        {"ประมาณ ", "About "},
        {"วัดได้ ", "Measured "},
        {" ม.", " m"},
        {"หน่วงภาพกล้องตอนเดิน", "Walking camera delay"},
        {"ระยะมือที่รับ", "Hand range"},
        {"ล็อกมือผู้ใช้ (ตามมือที่อยู่ในภาพต่อเนื่อง 1 วินาที มือใหม่จากที่อื่นไม่รับ)", "Lock onto my hands (after 1 s in view; new hands from elsewhere are ignored)"},
        {"มองในที่มืด", "Night view"},
        {"สูง", "High"},
        {"หยุด", "Pause"},
        {"แอปนี้ไม่ยอมอยู่ในหน้าต่างลอย (เด้งไปจอจริง)", "This app will not stay in a floating window (it jumps to the real screen)"},
        {"ใช้แบบลอยไม่ได้ ลองแอปอื่น หรือกด ⟳", "Floating does not work here. Try another app, or press Refresh"},
        {"ถ้ายังไม่ขึ้น: แอปนี้อาจไม่ยอมเปิดบนจอเสมือน หรือเด้งไปจอจริง", "If nothing shows: this app may refuse the virtual screen or jump to the real one"}
    };
    private static String[][] sorted;
    private static final HashMap<String, String> exact = new HashMap<>();

    private static synchronized void init() {
        if (sorted != null) return;
        String[][] s = PAIRS.clone();
        java.util.Arrays.sort(s, (a, b) -> b[0].length() - a[0].length());      // the longest wording first, so "Off (fastest)" is not turned into "Off" + the rest
        for (String[] p : PAIRS) exact.put(p[0], p[1]);
        sorted = s;
    }

    public static void setThai(boolean t) { thai = t; synchronized (Tr.class) { cache.clear(); } }

    /** characters that are small pictures (emoji and pictograms), taken out; arrows, tick marks and the like stay */
    private static String stripEmoji(String s) {
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isSurrogate(c) || c == 0xFE0F || c == 0x200D || (c >= 0x2600 && c <= 0x27BF && c != 0x2713 && c != 0x2714 && c != 0x2715 && c != 0x2716) || (c >= 0x2300 && c <= 0x23FF) || (c >= 0x2B00 && c <= 0x2BFF)) { any = true; break; }
        }
        if (!any) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length()) { i++; continue; }
            if (Character.isSurrogate(c) || c == 0xFE0F || c == 0x200D) continue;
            if ((c >= 0x2600 && c <= 0x27BF && c != 0x2713 && c != 0x2714 && c != 0x2715 && c != 0x2716) || (c >= 0x2300 && c <= 0x23FF) || (c >= 0x2B00 && c <= 0x2BFF)) continue;
            b.append(c);
        }
        return b.toString().replaceAll("^\\s+|\\s+$", "").replaceAll("\\s{3,}", "  ");
    }

    /** a text as it is shown: translated (English) and without emoji */
    public static String t(String s) {
        if (s == null || s.isEmpty()) return s;
        synchronized (Tr.class) {
            String c = cache.get(s);
            if (c != null) return c;
        }
        String r = stripEmoji(s);
        if (!thai) {
            init();
            String ex = exact.get(r);
            if (ex != null) r = ex;
            else {
                boolean hasThai = false;
                for (int i = 0; i < r.length(); i++) if (r.charAt(i) >= 0x0E00 && r.charAt(i) <= 0x0E7F) { hasThai = true; break; }
                if (hasThai) for (String[] p : sorted) if (r.contains(p[0])) r = r.replace(p[0], p[1]);
            }
        }
        synchronized (Tr.class) { if (cache.size() > 800) cache.clear(); cache.put(s, r); }
        return r;
    }

    /** the careful version, for texts that may hold the user's own words (page titles, file names): a whole text or a fixed start ("Error: ...") is translated, nothing inside */
    public static String x(String s) {
        if (s == null || s.isEmpty() || thai) return s;
        init();
        String r = stripEmoji(s);
        String ex = exact.get(r);
        if (ex != null) return ex;
        for (String[] p : sorted) if (p[0].length() > 3 && (p[0].endsWith(": ") || p[0].endsWith(" ")) && r.startsWith(p[0])) return p[1] + r.substring(p[0].length());
        return s;
    }
}
