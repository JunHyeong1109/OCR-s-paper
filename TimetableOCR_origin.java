import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 시간표 이미지 → JSON 변환기 (로컬 Tesseract OCR 기반)
 *
 * 사용법:
 *   java -jar timetable-ocr.jar <이미지> [output.json]
 *
 * Tesseract 설치 필요:
 *   macOS : brew install tesseract tesseract-lang
 *   Ubuntu: sudo apt install tesseract-ocr tesseract-ocr-kor
 *   Windows: https://github.com/UB-Mannheim/tesseract/wiki
 */
public class TimetableOCR {

    private static final Map<String, String> DAY_MAP = new LinkedHashMap<>();
    private static final Set<String> TIME_LABELS =
            new HashSet<>(Arrays.asList("8","9","10","11","12","1","2","3","4","5","6","7"));

    // 강의실/건물 코드 패턴 (단어 단위 매칭) — 이 중 하나라도 포함된 줄은 통째로 skip
    private static final Pattern ROOM_CODE = Pattern.compile(
        "^[가-힣]{1,4}\\d{2,5}$" +               // 백201, 이대관4503
        "|^[가-힣]{1,4}\\d{2,5}-\\d{1,4}$" +     // 백612-1
        "|^[a-zA-Z]{1,3}\\d{1,4}$" +              // E101, B301, N1, E1302
        "|^[a-zA-Z]{1,2}\\d{1,3}-\\d{1,4}$" +    // E22-224, B03-101, S102-1
        "|^\\d{3,5}$" +                            // 4503, 3017
        "|^\\d+호$" +                              // 4401호, 201호
        "|^[가-힣]{2,6}관$" +                     // 인애관, 공학관
        "|^[가-힣]{2,6}호관$" +                   // 농대4호관 (all-Korean)
        "|^[가-힣]{1,2}\\d+호관$" +                // 농대4호관, 공대1호관 (Korean+digit+호관)
        "|^\\d+/\\d+$"                            // 2/101, 3/201 강의실 코드
    );

    // GPA 패턴: 3.79/4.5, 3.7974.5(OCR오인), 4.0/4.5 등
    private static final java.util.regex.Pattern GPA_PATTERN =
        java.util.regex.Pattern.compile("^\\d+\\.\\d{2,}");

    static {
        DAY_MAP.put("월", "MONDAY");
        DAY_MAP.put("화", "TUESDAY");
        DAY_MAP.put("수", "WEDNESDAY");
        DAY_MAP.put("목", "THURSDAY");
        DAY_MAP.put("금", "FRIDAY");
        DAY_MAP.put("토", "SATURDAY");
    }

    // ──────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -jar timetable-ocr.jar <image_path> [output.json]");
            System.exit(1);
        }

        String imagePath  = args[0];
        String outputPath = args.length >= 2 ? args[1] : null;

        BufferedImage image = loadImage(imagePath);
        if (image == null) {
            System.err.println("이미지를 읽을 수 없습니다: " + imagePath);
            System.err.println("지원 형식: PNG, JPG/JPEG, GIF, BMP, WEBP");
            System.exit(1);
        }

        // ── 다크모드 감지 → OCR용 이미지 준비 (다크: 반전) ───────────────────
        boolean darkMode = isImageDark(image);
        BufferedImage ocrImage = darkMode ? invertImage(image) : image;

        Tesseract tess = buildTesseract();

        // ── 1단계: 전체 이미지 OCR (한/영) → 요일 헤더 + 과목명 단어 ──────────
        System.err.println("[1/3] 레이아웃 분석 중... (darkMode=" + darkMode + ")");
        List<Word> allWords = tess.getWords(ocrImage, ITessAPI.TessPageIteratorLevel.RIL_WORD);

        // 요일 헤더: 각 요일의 가장 위(topmost) 출현만 사용
        // dayHeaderMaxY = topmost 출현들의 하단 y 최대값 (헤더 행의 바닥)
        Map<String, Rectangle> dayHeaderBoxes = new LinkedHashMap<>();
        for (Word w : allWords) {
            String t = w.getText().trim();
            Rectangle b = w.getBoundingBox();
            if (DAY_MAP.containsKey(t)) {
                if (!dayHeaderBoxes.containsKey(t) || b.y < dayHeaderBoxes.get(t).y) {
                    dayHeaderBoxes.put(t, b);
                }
            }
        }
        Map<String, Integer> dayColX = new TreeMap<>();
        int dayHeaderMaxY = 0;
        for (Map.Entry<String, Rectangle> e : dayHeaderBoxes.entrySet()) {
            Rectangle b = e.getValue();
            dayColX.put(e.getKey(), b.x + b.width / 2);
            dayHeaderMaxY = Math.max(dayHeaderMaxY, b.y + b.height);
        }

        // 요일 헤더가 없으면 fallback
        if (dayColX.isEmpty()) {
            System.err.println("  경고: 요일 헤더를 찾지 못했습니다.");
        }
        System.err.println("  요일 컬럼: " + dayColX.keySet());

        // bfsTopBound: 요일 헤더 아래부터 (헤더 없으면 이미지 상단 5%)
        int bfsTopBound = dayHeaderMaxY > 0
                ? dayHeaderMaxY + 5
                : image.getHeight() / 20;

        // timeColW: 가장 왼쪽 요일 컬럼 시작점 추정
        int timeColW = computeTimeColWidth(dayColX, image.getWidth());

        // ── 시간 레이블 탐색 (영어 전용 OCR + 전체 이미지 보완) ──────────────
        // timeColW가 과대추정되면 시간 레이블 열에 강의 블록 내용까지 포함돼 오인식 발생
        // → 더 좁은 timeStripW(image폭/12 이하)만 스캔하여 실제 시간 숫자만 인식
        int timeStripW = Math.min(timeColW, image.getWidth() / 12);
        TreeMap<Integer, Integer> timeRowY = new TreeMap<>();
        BufferedImage leftStrip = ocrImage.getSubimage(0, 0, timeStripW, ocrImage.getHeight());
        tess.setLanguage("eng");
        List<Word> leftWords = tess.getWords(leftStrip, ITessAPI.TessPageIteratorLevel.RIL_WORD);
        tess.setLanguage("kor+eng");

        int minTimeY = bfsTopBound; // 요일 헤더 아래부터만 시간 레이블 탐색 (헤더의 숫자 오인식 방지)
        for (Word w : leftWords) {
            String t = w.getText().trim();
            Rectangle b = w.getBoundingBox();
            if (b.y > minTimeY && TIME_LABELS.contains(t)) {
                int actual = toActualHour(Integer.parseInt(t));
                timeRowY.putIfAbsent(actual, b.y);
            }
        }
        // 전체 이미지 OCR 결과로 보완 (timeStripW 기준으로 필터)
        for (Word w : allWords) {
            String t = w.getText().trim();
            Rectangle b = w.getBoundingBox();
            if (b.x + b.width < timeStripW + 10 && b.y > minTimeY && TIME_LABELS.contains(t)) {
                int actual = toActualHour(Integer.parseInt(t));
                timeRowY.putIfAbsent(actual, b.y);
            }
        }

        // 시간 레이블 부족 시 더 넓은 스트립으로 재시도
        if (timeRowY.size() < 2) {
            System.err.println("  시간 레이블 부족 (" + timeRowY.size() + "개), 넓은 스트립으로 재시도...");
            int widerW = Math.min(image.getWidth() / 5, image.getWidth());
            BufferedImage widerStrip = ocrImage.getSubimage(0, 0, widerW, ocrImage.getHeight());
            tess.setLanguage("eng");
            List<Word> widerWords = tess.getWords(widerStrip, ITessAPI.TessPageIteratorLevel.RIL_WORD);
            tess.setLanguage("kor+eng");
            for (Word w : widerWords) {
                String t = w.getText().trim();
                Rectangle b = w.getBoundingBox();
                if (b.y > minTimeY && TIME_LABELS.contains(t)) {
                    int actual = toActualHour(Integer.parseInt(t));
                    timeRowY.putIfAbsent(actual, b.y);
                }
            }
            System.err.println("  재시도 후 시간 레이블: " + timeRowY.size() + "개");
        }

        // ── 2단계: 컬러 블록 검출 (원본 이미지 기준) ─────────────────────────
        System.err.println("[2/3] 과목 블록 검출 중...");
        int minBlockPixels = Math.max(200, image.getWidth() * image.getHeight() / 8000);
        // 항상 원본 이미지로 BFS. 다크모드에서는 밝기 하한을 낮춰(30) 어두운 색상 블록도 검출
        int bfsMinBrightness = darkMode ? 30 : 60;
        int bfsMinSat = darkMode ? 6 : 20;
        List<Rectangle> blocks = detectColoredBlocks(image, 0, bfsTopBound, minBlockPixels, bfsMinBrightness, bfsMinSat);
        // 일부 요일 컬럼이 OCR로 미검출된 경우 timeColW 과대추정 가능 → 가장 왼쪽 블록 시작 위치로 보정
        // (블록의 좌측 끝이 현재 timeColW보다 훨씬 왼쪽에 있으면 시간 컬럼이 실제로는 더 좁음)
        if (!blocks.isEmpty()) {
            int leftmostX = blocks.stream().mapToInt(b -> b.x).min().orElse(timeColW);
            if (leftmostX < timeColW - 10) {
                timeColW = Math.max(20, leftmostX - 5);
            }
        }
        // ── 미검출 요일 컬럼 추론 ─────────────────────────────────────────────
        // 블록 x 위치 기반으로 OCR가 놓친 컬럼(왼쪽/오른쪽) 보완
        if (dayColX.size() >= 2 && !blocks.isEmpty()) {
            java.util.List<String> DAY_ORDER = java.util.Arrays.asList("월", "화", "수", "목", "금", "토");
            int minDayX2 = dayColX.values().stream().mapToInt(Integer::intValue).min().orElse(0);
            int maxDayX2 = dayColX.values().stream().mapToInt(Integer::intValue).max().orElse(image.getWidth());
            int colWidth2 = (maxDayX2 - minDayX2) / (dayColX.size() - 1);
            // 왼쪽 누락 컬럼: 감지된 최좌측 컬럼보다 colWidth/2 이상 왼쪽에 블록이 있으면 추론
            int leftThreshold = minDayX2 - colWidth2 / 2;
            final int capturedTimeColW = timeColW;
            boolean hasBlocksLeft = blocks.stream()
                    .anyMatch(b -> (b.x + b.width / 2) > capturedTimeColW && (b.x + b.width / 2) < leftThreshold);
            if (hasBlocksLeft) {
                String leftmostDay = dayColX.entrySet().stream()
                        .min(Comparator.comparingInt(Map.Entry::getValue)).get().getKey();
                int leftIdx = DAY_ORDER.indexOf(leftmostDay);
                if (leftIdx > 0) {
                    String missingDay = DAY_ORDER.get(leftIdx - 1);
                    double inferredX = blocks.stream()
                            .filter(b -> (b.x + b.width / 2) > capturedTimeColW && (b.x + b.width / 2) < leftThreshold)
                            .mapToDouble(b -> b.x + b.width / 2.0).average()
                            .orElse(minDayX2 - colWidth2);
                    dayColX.put(missingDay, (int) inferredX);
                    System.err.println("  미검출 요일 컬럼 추론 (왼쪽): " + missingDay + " @ x=" + (int) inferredX);
                }
            }
            // 오른쪽 누락 컬럼: 감지된 최우측 컬럼보다 colWidth/2 이상 오른쪽에 블록이 있으면 추론
            int rightThreshold = maxDayX2 + colWidth2 / 2;
            boolean hasBlocksRight = blocks.stream()
                    .anyMatch(b -> (b.x + b.width / 2) > rightThreshold);
            if (hasBlocksRight) {
                String rightmostDay = dayColX.entrySet().stream()
                        .max(Comparator.comparingInt(Map.Entry::getValue)).get().getKey();
                int rightIdx = DAY_ORDER.indexOf(rightmostDay);
                if (rightIdx >= 0 && rightIdx < DAY_ORDER.size() - 1) {
                    String missingDay = DAY_ORDER.get(rightIdx + 1);
                    double inferredX = blocks.stream()
                            .filter(b -> (b.x + b.width / 2) > rightThreshold)
                            .mapToDouble(b -> b.x + b.width / 2.0).average()
                            .orElse(maxDayX2 + colWidth2);
                    dayColX.put(missingDay, (int) inferredX);
                    System.err.println("  미검출 요일 컬럼 추론 (오른쪽): " + missingDay + " @ x=" + (int) inferredX);
                }
            }
        }

        // ── 시간표 경계 계산 ──────────────────────────────────────────────────
        // 좌측: 시간 레이블 열 우측 경계 (이미 계산됨)
        final int finalTimeColW = timeColW;

        // 우측: 가장 오른쪽 요일 컬럼 우측 끝 + 여유
        int rightBound = image.getWidth();
        if (dayColX.size() >= 2) {
            int minDayX = dayColX.values().stream().mapToInt(Integer::intValue).min().orElse(0);
            int maxDayX = dayColX.values().stream().mapToInt(Integer::intValue).max().orElse(image.getWidth());
            int colWidth = (maxDayX - minDayX) / (dayColX.size() - 1);
            // 미검출 요일(금요일 등) 1열 추가 허용 후 우측 경계 설정
            rightBound = maxDayX + colWidth + colWidth / 2;
        }

        // 하단: 마지막 시간 행 y + 1.5시간 분량 픽셀 추정
        int bottomTimeBound = image.getHeight() - image.getHeight() / 25; // fallback: 하단 4%
        if (timeRowY.size() >= 2) {
            List<int[]> trows = new ArrayList<>();
            timeRowY.forEach((h, y) -> trows.add(new int[]{h, y}));
            trows.sort(Comparator.comparingInt(a -> a[1]));
            double pph = (double)(trows.get(trows.size()-1)[1] - trows.get(0)[1])
                       / (trows.get(trows.size()-1)[0] - trows.get(0)[0]);
            bottomTimeBound = trows.get(trows.size()-1)[1] + (int)(pph * 1.5);
        }

        // 시간 레이블 검출 완전 실패 시 블록 위치로 시간 그리드 추정
        if (timeRowY.size() < 2 && !blocks.isEmpty()) {
            System.err.println("  시간 레이블 검출 실패 - 블록 위치로 시간 그리드 추정");
            int contentTop = bfsTopBound;
            int contentBottom = bottomTimeBound;
            int contentHeight = contentBottom - contentTop;
            // 일반적인 시간표: 8시~18시 = 10시간 span 가정
            int startHour = 9, totalHours = 9; // 기본값 9~18시
            double pph = (double) contentHeight / totalHours;
            for (int h = 0; h < totalHours; h++) {
                timeRowY.put(startHour + h, contentTop + (int)(h * pph));
            }
            System.err.println("  추정 그리드: " + startHour + "시~" + (startHour + totalHours) + "시, " + (int)pph + "px/h");
        }

        // 시간 레이블 열 / 시간표 우측 바깥 / 하단 바깥 블록 제거
        blocks.removeIf(b -> b.x + b.width < finalTimeColW + 5);
        final int finalRightBound = rightBound;
        blocks.removeIf(b -> b.x + b.width / 2 > finalRightBound);
        final int finalBottomBound = bottomTimeBound;
        blocks.removeIf(b -> b.y + b.height / 2 > finalBottomBound);
        System.err.println("  " + blocks.size() + "개 블록 발견");

        // ── 3단계: 단어-블록 매핑 → JSON 생성 ──────────────────────────────
        System.err.println("[3/3] 단어-블록 매핑 중...");
        List<Map<String, String>> schedules = new ArrayList<>();

        for (Rectangle block : blocks) {
            // 블록 내 단어 수집 — 시간표 경계(x: timeColW~rightBound, y: bfsTopBound~bottomTimeBound) 내 단어만 허용
            List<Word> inside = new ArrayList<>();
            Rectangle expanded = new Rectangle(block.x - 8, block.y - 8, block.width + 16, block.height + 16);
            for (Word w : allWords) {
                Rectangle wb = w.getBoundingBox();
                int cx = wb.x + wb.width  / 2;
                int cy = wb.y + wb.height / 2;
                // 시간표 영역(좌측·우측·상단·하단 경계) 바깥 단어 제외
                if (cx < finalTimeColW || cx > finalRightBound) continue;
                if (cy < bfsTopBound  || cy > finalBottomBound) continue;
                if (expanded.contains(cx, cy)) inside.add(w);
            }

            long singleKoreanCount = inside.stream()
                .filter(w -> w.getText().trim().matches("[가-힣]")).count();
            String subject = buildSubjectName(inside);

            // 블록 단위 OCR fallback: 한글이 없을 때만 실행 (속도 최적화)
            int subKor = (int) subject.chars().filter(c -> c >= 0xAC00 && c <= 0xD7A3).count();
            if (subKor == 0) {
                String fallback = fallbackBlockOCR(tess, image, ocrImage, block, darkMode);
                int fbKor = (int) fallback.chars().filter(c -> c >= 0xAC00 && c <= 0xD7A3).count();
                if (fbKor > 0 || (!fallback.isEmpty() && subject.isEmpty())) subject = fallback;
            }
            if (subject.isEmpty()) continue;
            // 너무 짧은 과목명 제거 (단순 노이즈)
            if (subject.length() < 2) continue;
            // 1-3자 라틴 문자만 있는 경우 (Ey, aU, iz 등 아이콘 노이즈)
            if (subject.matches("[a-zA-Z]{1,4}")) continue;
            // GPA 표시 ("3.79/4.5", "3.7974.5" 등) 제거
            if (GPA_PATTERN.matcher(subject).find()) continue;
            // 숫자/기호로만 구성된 경우
            if (subject.matches("[\\d\\s/\\\\.,+\\-=|@#$%^&*()]+")) continue;
            // 명백한 OCR 쓰레기 문자 포함 (], |, { 등)
            if (subject.matches(".*[\\]\\[|{}].*")) continue;
            // 소문자 알파벳 시작 + 괄호/숫자 포함 (e.g. "e (2 )") → OCR 쓰레기
            if (subject.matches("[a-z]\\s*\\(.*")) continue;

            String   day   = assignDay(block, dayColX);
            if (day == null) continue;

            String[] times = assignTimes(block, timeRowY);
            if (times == null) continue;
            // 시작==종료 또는 45분 미만 블록 제거 (대학 강의 최소 단위 기준)
            if (times[0].equals(times[1])) continue;
            if (timeToMinutes(times[1]) - timeToMinutes(times[0]) < 45) continue;

            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("dayOfWeek",   day);
            entry.put("startTime",   times[0]);
            entry.put("endTime",     times[1]);
            entry.put("subjectName", subject.replaceAll("\\s+", ""));
            schedules.add(entry);
        }

        // 과목명 정규화: 한 과목명이 다른 과목명의 접두어인 경우 짧은 쪽을 긴 쪽으로 통일
        // (예: "생태문학의이" + "생태문학의이해" → 모두 "생태문학의이해")
        List<String> distinctSubjects = schedules.stream()
                .map(m -> m.get("subjectName"))
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .collect(java.util.stream.Collectors.toList());
        Map<String, String> normalize = new java.util.HashMap<>();
        for (int i = 0; i < distinctSubjects.size(); i++) {
            for (int j = i + 1; j < distinctSubjects.size(); j++) {
                String longer  = distinctSubjects.get(i);
                String shorter = distinctSubjects.get(j);
                if (shorter.length() >= 4 && longer.startsWith(shorter)) {
                    normalize.put(shorter, longer);
                }
            }
        }
        if (!normalize.isEmpty()) {
            for (Map<String, String> entry : schedules) {
                String s = entry.get("subjectName");
                if (normalize.containsKey(s)) entry.put("subjectName", normalize.get(s));
            }
        }

        // 중복 제거 (같은 요일+시작시간+과목명)
        Set<String> seen = new LinkedHashSet<>();
        schedules.removeIf(m -> !seen.add(
                m.get("dayOfWeek") + "|" + m.get("startTime") + "|" + m.get("subjectName")));

        // OCR 노이즈 제거: 한글 글자 수 1~2인 짧은 과목명이 서로 다른 startTime에 2회 이상 등장하면 제거
        // (실제 단강좌는 같은 시간 여러 요일에 반복되지만, "레가" 같은 OCR 노이즈는 다른 시간대에 등장)
        // 순수 영어 과목명(korChars==0)은 제외: "Academic English", "ENGLISH" 등
        {
            Map<String, Set<String>> subjectTimes = new java.util.HashMap<>();
            for (Map<String, String> m : schedules) {
                String subj = m.get("subjectName");
                int korChars = (int) subj.chars().filter(c -> c >= 0xAC00 && c <= 0xD7A3).count();
                if (korChars >= 1 && korChars <= 2) {
                    subjectTimes.computeIfAbsent(subj, k -> new java.util.HashSet<>())
                                .add(m.get("startTime"));
                }
            }
            Set<String> noiseSubjects = new java.util.HashSet<>();
            for (Map.Entry<String, Set<String>> e : subjectTimes.entrySet()) {
                if (e.getValue().size() >= 2) noiseSubjects.add(e.getKey());
            }
            if (!noiseSubjects.isEmpty()) {
                System.err.println("  OCR 노이즈 과목명 제거: " + noiseSubjects);
                schedules.removeIf(m -> noiseSubjects.contains(m.get("subjectName")));
            }
        }

        // 요일 → 시작 시간 순 정렬
        Map<String, Integer> dayOrder = Map.of(
                "MONDAY",0,"TUESDAY",1,"WEDNESDAY",2,"THURSDAY",3,"FRIDAY",4,"SATURDAY",5);
        schedules.sort(
                Comparator.comparingInt((Map<String,String> m) ->
                        dayOrder.getOrDefault(m.get("dayOfWeek"), 9))
                .thenComparing(m -> m.get("startTime"))
        );

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        String json = gson.toJson(schedules);

        if (outputPath != null) {
            Files.writeString(Path.of(outputPath), json);
            System.err.println("저장 완료: " + outputPath);
        } else {
            System.out.println(json);
        }
        System.err.println("완료: " + schedules.size() + "개 항목");
    }

    // ── 다크모드 감지 ────────────────────────────────────────────────────────

    /**
     * 다양한 이미지 형식(PNG, JPEG, GIF, BMP 등)을 읽어 TYPE_INT_RGB로 변환 반환.
     * JPEG는 내부적으로 TYPE_3BYTE_BGR 또는 TYPE_BYTE_GRAY로 로드되므로 통일 필요.
     */
    static BufferedImage loadImage(String path) throws Exception {
        BufferedImage img = ImageIO.read(new File(path));
        if (img == null) return null;
        // 이미 TYPE_INT_RGB면 그대로 반환
        if (img.getType() == BufferedImage.TYPE_INT_RGB) return img;
        // 그 외 포맷(BGR, GRAY, RGBA 등) → TYPE_INT_RGB 변환
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = rgb.createGraphics();
        g.setBackground(java.awt.Color.WHITE);
        g.clearRect(0, 0, img.getWidth(), img.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return rgb;
    }

    static boolean isImageDark(BufferedImage img) {
        int W = img.getWidth(), H = img.getHeight();
        long sum = 0; int count = 0;
        int step = Math.max(1, Math.min(W, H) / 30);
        for (int y = 0; y < H; y += step)
            for (int x = 0; x < W; x += step) {
                int rgb = img.getRGB(x, y);
                sum += ((rgb>>16)&0xFF) + ((rgb>>8)&0xFF) + (rgb&0xFF);
                count += 3;
            }
        return count > 0 && sum / count < 100;
    }

    // ── 이미지 반전 ──────────────────────────────────────────────────────────

    static BufferedImage invertImage(BufferedImage src) {
        int W = src.getWidth(), H = src.getHeight();
        int[] pixels = src.getRGB(0, 0, W, H, null, 0, W);
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            pixels[i] = (p & 0xFF000000)
                    | ((255 - ((p>>16)&0xFF)) << 16)
                    | ((255 - ((p>>8)&0xFF)) << 8)
                    | (255 - (p&0xFF));
        }
        BufferedImage result = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, W, H, pixels, 0, W);
        return result;
    }

    // ── Tesseract 초기화 ─────────────────────────────────────────────────────

    static Tesseract buildTesseract() {
        Tesseract tess = new Tesseract();
        String tessData = System.getenv("TESSDATA_PREFIX");
        if (tessData == null) {
            for (String p : new String[]{
                    "/opt/homebrew/share/tessdata",
                    "/usr/share/tessdata",
                    "/usr/local/share/tessdata",
                    "/usr/share/tesseract-ocr/4.00/tessdata",
                    "/usr/share/tesseract-ocr/5/tessdata",
                    "tessdata"}) {
                if (new File(p, "kor.traineddata").exists()) { tessData = p; break; }
            }
            if (tessData == null) tessData = "tessdata";
        }
        tess.setDatapath(tessData);
        tess.setLanguage("kor+eng");
        tess.setOcrEngineMode(ITessAPI.TessOcrEngineMode.OEM_LSTM_ONLY);
        tess.setTessVariable("user_defined_dpi", "300");
        return tess;
    }

    // ── 시간 레이블 열 폭 계산 ────────────────────────────────────────────────

    static int computeTimeColWidth(Map<String, Integer> dayColX, int imageWidth) {
        if (dayColX.size() >= 2) {
            // 요일 컬럼 간격 = (최우측 - 최좌측) / (컬럼수 - 1)
            int minX = dayColX.values().stream().mapToInt(Integer::intValue).min().orElse(imageWidth/5);
            int maxX = dayColX.values().stream().mapToInt(Integer::intValue).max().orElse(imageWidth*4/5);
            int colSpan = maxX - minX;
            int numCols = dayColX.size() - 1;
            int colWidth = numCols > 0 ? colSpan / numCols : colSpan;
            int leftEdgeOfFirstCol = minX - colWidth / 2;
            return Math.max(20, leftEdgeOfFirstCol - 5);
        }
        return imageWidth / 11;
    }

    // ── 24시간제 변환 ─────────────────────────────────────────────────────────
    static int toActualHour(int grid) { return (grid >= 8) ? grid : grid + 12; }

    // ── 컬러 블록 BFS 검출 (채도 기반: 라이트/다크모드 공용) ─────────────────

    static List<Rectangle> detectColoredBlocks(BufferedImage img,
                                                int leftBound, int topBound,
                                                int minPixels, int minBrightness, int minSat) {
        int W = img.getWidth(), H = img.getHeight();
        int[] pixels  = img.getRGB(0, 0, W, H, null, 0, W);
        boolean[] vis = new boolean[W * H];
        List<Rectangle> result = new ArrayList<>();
        int[] dx = {1,-1,0,0}, dy = {0,0,1,-1};

        for (int sy = topBound; sy < H; sy++) {
            for (int sx = leftBound; sx < W; sx++) {
                int idx = sy * W + sx;
                if (vis[idx] || !isColored(pixels[idx], minBrightness, minSat)) { vis[idx] = true; continue; }

                ArrayDeque<Integer> q = new ArrayDeque<>();
                q.add(idx); vis[idx] = true;
                int minX = sx, maxX = sx, minY = sy, maxY = sy, count = 0;

                while (!q.isEmpty()) {
                    int cur = q.poll();
                    int cx = cur % W, cy = cur / W;
                    if (cx < minX) minX = cx; if (cx > maxX) maxX = cx;
                    if (cy < minY) minY = cy; if (cy > maxY) maxY = cy;
                    count++;
                    for (int d = 0; d < 4; d++) {
                        int nx = cx+dx[d], ny = cy+dy[d];
                        if (nx >= 0 && nx < W && ny >= topBound && ny < H) {
                            int ni = ny*W+nx;
                            if (!vis[ni] && isColored(pixels[ni], minBrightness, minSat)) { vis[ni]=true; q.add(ni); }
                        }
                    }
                }
                if (count >= minPixels)
                    result.add(new Rectangle(minX, minY, maxX-minX+1, maxY-minY+1));
            }
        }
        return result;
    }

    static List<Rectangle> detectColoredBlocks(BufferedImage img,
                                                int leftBound, int topBound,
                                                int minPixels, int minBrightness) {
        return detectColoredBlocks(img, leftBound, topBound, minPixels, minBrightness, 20);
    }

    static List<Rectangle> detectColoredBlocks(BufferedImage img,
                                                int leftBound, int topBound,
                                                int minPixels) {
        return detectColoredBlocks(img, leftBound, topBound, minPixels, 60, 20);
    }

    /**
     * 채도 기반 컬러 픽셀 판별.
     * minBrightness: 라이트모드=60, 다크모드=30 (어두운 색상 블록 검출 허용)
     * minSat: 라이트모드=20, 다크모드=8 (다크모드 낮은채도 블록 검출)
     * - brightness > minBrightness AND brightness < 225 AND sat > minSat
     */
    static boolean isColored(int rgb, int minBrightness, int minSat) {
        int r = (rgb>>16)&0xFF, g = (rgb>>8)&0xFF, b = rgb&0xFF;
        int brightness = (r + g + b) / 3;
        int sat = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
        return brightness > minBrightness && brightness < 225 && sat > minSat;
    }

    static boolean isColored(int rgb, int minBrightness) {
        return isColored(rgb, minBrightness, 20);
    }

    static boolean isColored(int rgb) {
        return isColored(rgb, 60, 20);
    }

    // ── 블록 단위 OCR fallback (ocrImage crop) ──────────────────────────────

    /**
     * 블록 단위 OCR fallback.
     * original: 원본 이미지 (반전 전), ocrImage: 전처리된 OCR용 이미지 (라이트모드=원본, 다크모드=반전)
     * 두 소스 모두 시도하여 한글 글자 수가 많은 결과 반환.
     */
    static String fallbackBlockOCR(Tesseract tess, BufferedImage original,
                                   BufferedImage ocrImage, Rectangle block, boolean darkMode) {
        String best = "";
        int bestKor = 0;

        // 전략 0: RGB 채도 기반 흰 텍스트 추출 (모든 모드)
        // 흰 텍스트(R≈G≈B≈255) vs 컬러 배경(채도 있음) 구분
        // 안드로이드 시간표 앱처럼 흰 글자 + 컬러 배경 조합에 특히 효과적
        try {
            int x = Math.max(0, block.x);
            int y = Math.max(0, block.y);
            int w = Math.min(original.getWidth()  - x, block.width);
            int h = Math.min(original.getHeight() - y, block.height);
            if (w < 5 || h < 5) throw new Exception("block too small");

            int[] rgbPixels = new int[w * h];
            original.getSubimage(x, y, w, h).getRGB(0, 0, w, h, rgbPixels, 0, w);
            int[] bin = new int[w * h];
            for (int i = 0; i < rgbPixels.length; i++) {
                int r = (rgbPixels[i] >> 16) & 0xFF;
                int g = (rgbPixels[i] >>  8) & 0xFF;
                int b = rgbPixels[i] & 0xFF;
                int brightness = (r + g + b) / 3;
                int sat = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                // 밝고 채도 낮은 픽셀 = 흰색/밝은 텍스트
                boolean isWhiteText = brightness > 160 && sat < 60;
                bin[i] = isWhiteText ? 0x000000 : 0xFFFFFF;
            }
            BufferedImage binary = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            binary.setRGB(0, 0, w, h, bin, 0, w);

            String r = ocrBlock(tess, binary);
            int kor = korCount(r);
            if (kor > bestKor || (kor == bestKor && !r.isEmpty()
                    && (best.isEmpty() || r.length() < best.length()))) {
                best = r; bestKor = kor;
            }
        } catch (Exception ignored) {}

        // 전략 1: 원본 이미지 블록 영역만 크롭 → Otsu 이진화
        try {
            int x = Math.max(0, block.x);
            int y = Math.max(0, block.y);
            int w = Math.min(original.getWidth()  - x, block.width);
            int h = Math.min(original.getHeight() - y, block.height);
            if (w < 5 || h < 5) throw new Exception("block too small");

            BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            java.awt.Graphics2D gg = gray.createGraphics();
            gg.drawImage(original.getSubimage(x, y, w, h), 0, 0, null);
            gg.dispose();
            int[] gp = new int[w * h];
            gray.getRaster().getPixels(0, 0, w, h, gp);
            gp = stretchHistogram(gp);

            int otsu = computeOtsuThreshold(gp);
            long darkCnt = 0, brightCnt = 0;
            for (int p : gp) { if (p <= otsu) darkCnt++; else brightCnt++; }
            boolean textIsBright = darkCnt > brightCnt;
            int[] bin = new int[gp.length];
            for (int i = 0; i < gp.length; i++) {
                boolean isText = textIsBright ? gp[i] > otsu : gp[i] <= otsu;
                bin[i] = isText ? 0x000000 : 0xFFFFFF;
            }
            BufferedImage binary = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            binary.setRGB(0, 0, w, h, bin, 0, w);

            String r = ocrBlock(tess, binary);
            int kor = korCount(r);
            if (kor > bestKor || (kor == bestKor && !r.isEmpty()
                    && (best.isEmpty() || r.length() < best.length()))) {
                best = r; bestKor = kor;
            }
        } catch (Exception ignored) {}

        // 전략 2: 전처리 이미지(ocrImage) 블록 영역 크롭 → Otsu 이진화 (bestKor==0일 때만)
        // 다크모드: ocrImage=전역반전 → 배경=중간밝기, 텍스트=어둠. 패딩 없이 동일하게 적용
        if (ocrImage != null && bestKor == 0) {
            try {
                int x = Math.max(0, block.x);
                int y = Math.max(0, block.y);
                int w = Math.min(ocrImage.getWidth()  - x, block.width);
                int h = Math.min(ocrImage.getHeight() - y, block.height);
                if (w < 5 || h < 5) throw new Exception("block too small");

                BufferedImage gray2 = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
                java.awt.Graphics2D gg2 = gray2.createGraphics();
                gg2.drawImage(ocrImage.getSubimage(x, y, w, h), 0, 0, null);
                gg2.dispose();
                int[] gp = new int[w * h];
                gray2.getRaster().getPixels(0, 0, w, h, gp);
                gp = stretchHistogram(gp); // 히스토그램 스트레칭 → 대비 강화

                int otsu = computeOtsuThreshold(gp);
                long darkCnt = 0, brightCnt = 0;
                for (int p : gp) { if (p <= otsu) darkCnt++; else brightCnt++; }
                boolean textIsBright = darkCnt > brightCnt;
                int[] bin = new int[gp.length];
                for (int i = 0; i < gp.length; i++) {
                    boolean isText = textIsBright ? gp[i] > otsu : gp[i] <= otsu;
                    bin[i] = isText ? 0x000000 : 0xFFFFFF;
                }
                BufferedImage binary = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                binary.setRGB(0, 0, w, h, bin, 0, w);

                String r = ocrBlock(tess, binary);
                int kor = korCount(r);
                if (kor > bestKor || (kor == bestKor && !r.isEmpty()
                        && (best.isEmpty() || r.length() < best.length()))) {
                    best = r; bestKor = kor;
                }
            } catch (Exception ignored) {}
        }

        // 전략 3: 다크모드 전용 — 고정 임계값으로 흰색 텍스트만 추출
        // 다크모드 블록은 흰색/밝은 텍스트 + 채색 배경 구조.
        // Otsu가 실패하는 저대비 블록에 대해 brightness > 180 기준으로 직접 추출.
        if (darkMode && bestKor == 0) {
            try {
                int x = Math.max(0, block.x);
                int y = Math.max(0, block.y);
                int w = Math.min(original.getWidth()  - x, block.width);
                int h = Math.min(original.getHeight() - y, block.height);
                if (w < 5 || h < 5) throw new Exception("block too small");

                BufferedImage gray3 = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
                java.awt.Graphics2D gg3 = gray3.createGraphics();
                gg3.drawImage(original.getSubimage(x, y, w, h), 0, 0, null);
                gg3.dispose();
                int[] gp = new int[w * h];
                gray3.getRaster().getPixels(0, 0, w, h, gp);

                // 밝기 분포로 임계값 자동 결정: 상위 20% 밝은 픽셀을 텍스트로 간주
                int[] sorted = gp.clone();
                Arrays.sort(sorted);
                int textThreshold = sorted[(int)(sorted.length * 0.80)];
                textThreshold = Math.max(textThreshold, 150); // 최소 150 보장

                int[] bin = new int[gp.length];
                for (int i = 0; i < gp.length; i++)
                    bin[i] = gp[i] >= textThreshold ? 0x000000 : 0xFFFFFF;

                BufferedImage binary = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                binary.setRGB(0, 0, w, h, bin, 0, w);

                String r = ocrBlock(tess, binary);
                int kor = korCount(r);
                if (kor > bestKor || (kor == bestKor && !r.isEmpty()
                        && (best.isEmpty() || r.length() < best.length()))) {
                    best = r; bestKor = kor;
                }
            } catch (Exception ignored) {}
        }

        return best;
    }

    /**
     * 이미지 확대 + 화이트 패딩 후 두 PSM 모드로 OCR, 최적 결과 반환.
     * 3x에서 한글 인식 실패 시 4x로 재시도.
     * 화이트 패딩: Tesseract가 이미지 가장자리 글자를 잘라먹는 문제 방지.
     */
    private static String ocrBlock(Tesseract tess, BufferedImage img) throws Exception {
        int[] psms = { ITessAPI.TessPageSegMode.PSM_SINGLE_BLOCK,
                       ITessAPI.TessPageSegMode.PSM_SPARSE_TEXT,
                       ITessAPI.TessPageSegMode.PSM_SINGLE_LINE };
        String best = "";
        int bestKor = 0;
        for (int scale : new int[]{3, 4}) {
            if (scale == 4 && bestKor > 0) break; // 3x에서 한글 인식 성공하면 4x 생략
            BufferedImage scaled = addWhitePadding(scaleUp(img, scale), 8);
            for (int psm : psms) {
                tess.setPageSegMode(psm);
                String raw = tess.doOCR(scaled).trim();
                String cleaned = cleanFallbackText(raw);
                int kor = korCount(cleaned);
                if (kor > bestKor || (kor == bestKor && !cleaned.isEmpty()
                        && (best.isEmpty() || cleaned.length() < best.length()))) {
                    best = cleaned; bestKor = kor;
                }
            }
        }
        tess.setPageSegMode(ITessAPI.TessPageSegMode.PSM_AUTO);
        return best;
    }

    /** 이미지 사방에 흰색 패딩 추가 (Tesseract 가장자리 글자 인식 향상) */
    static BufferedImage addWhitePadding(BufferedImage src, int pad) {
        int nw = src.getWidth() + pad * 2, nh = src.getHeight() + pad * 2;
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, nw, nh);
        g.drawImage(src, pad, pad, null);
        g.dispose();
        return out;
    }

    /**
     * 히스토그램 스트레칭: 픽셀 값 범위를 0-255로 확장하여 대비 강화.
     * 범위가 10 미만이면 단색에 가까운 이미지이므로 스트레칭 생략.
     */
    static int[] stretchHistogram(int[] pixels) {
        int min = 255, max = 0;
        for (int p : pixels) { int v = p & 0xFF; if (v < min) min = v; if (v > max) max = v; }
        if (max - min < 10) return pixels;
        int[] out = new int[pixels.length];
        double scale = 255.0 / (max - min);
        for (int i = 0; i < pixels.length; i++)
            out[i] = (int) Math.min(255, ((pixels[i] & 0xFF) - min) * scale);
        return out;
    }

    /** Otsu 이진화 임계값 계산 (그레이스케일 픽셀 배열 0-255) */
    static int computeOtsuThreshold(int[] pixels) {
        int[] hist = new int[256];
        for (int p : pixels) hist[p & 0xFF]++;
        int total = pixels.length;
        double sum = 0;
        for (int i = 0; i < 256; i++) sum += i * hist[i];
        double sumB = 0, wB = 0;
        double maxVar = 0;
        int threshold = 128;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            double wF = total - wB;
            if (wF == 0) break;
            sumB += t * hist[t];
            double mB = sumB / wB;
            double mF = (sum - sumB) / wF;
            double var = wB * wF * (mB - mF) * (mB - mF);
            if (var > maxVar) { maxVar = var; threshold = t; }
        }
        return threshold;
    }

    private static int korCount(String s) {
        return (int) s.chars().filter(c -> c >= 0xAC00 && c <= 0xD7A3).count();
    }

    static BufferedImage scaleUp(BufferedImage src, int factor) {
        int nw = src.getWidth() * factor, nh = src.getHeight() * factor;
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    static String cleanFallbackText(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("[\\n\\r]+")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            // GPA 줄 skip
            if (GPA_PATTERN.matcher(t).find()) continue;
            // 캠퍼스 / ??호관(suffix) 줄 skip — contains("호관") 은 "간호관리학" 오판 방지로 endsWith 사용
            if (t.contains("캠퍼스") || t.endsWith("호관")) continue;
            // 강의실 코드만 있는 줄 skip
            if (ROOM_CODE.matcher(t).matches()) continue;
            // 공백 구분 단어 중 강의실 코드 발견 시 그 앞까지만 사용
            String truncated = truncateAtRoomCode(t);
            if (truncated.isEmpty()) continue;
            String cleaned = truncated;
            cleaned = cleaned.replaceAll("^[^가-힣a-zA-Z0-9]+", "").trim();         // 선행 비문자 (숫자는 유지: 4차산업혁명, 3D프린팅 등)
            cleaned = cleaned.replaceAll("\\(\\d+\\)?$", "").trim();               // 후행 섹션번호
            cleaned = cleaned.replaceAll("[|=\\-]{1,3}[A-Za-z0-9]{0,3}$", "").trim(); // =I 등 노이즈
            cleaned = cleaned.replaceAll("[|=\\-~`'\"^*\\s]+$", "").trim();       // 후행 기호
            cleaned = cleaned.replaceAll("[^가-힣a-zA-Z0-9()\\s]+$", "").trim(); // 후행 비문자
            cleaned = cleaned.replaceAll("\\s+\\$?\\d+$", "").trim();               // 후행 숫자 "$3017", "24"
            cleaned = java.util.regex.Pattern.compile("\\s+[A-Z][A-Za-z]{2,}$")
                    .matcher(cleaned)
                    .replaceAll(mr -> {
                    String word = mr.group().trim();
                    String normalized = word.replace('l', 'I');
                    if (normalized.matches("[IV]+")) return " " + normalized;
                    if (word.chars().allMatch(c -> !Character.isLetter(c) || Character.isUpperCase(c))) {
                        String roman = normalized.replaceAll("^([IV]+).*", "$1");
                        if (!roman.isEmpty()) return " " + roman;
                        return mr.group();
                    }
                    String roman = normalized.replaceAll("^([IV]+).*", "$1");
                    return roman.isEmpty() ? "" : " " + roman;
                });
            cleaned = cleaned.trim();
            // 라인 단계: 한글 뒤 라틴 노이즈 제거 (s oO, soO, St 등 모든 패턴)
            String beforeClean = cleaned;
            cleaned = cleaned.replaceAll("(?<=[가-힣])[a-zA-Z]+(\\s+[a-zA-Z]+)*$", "").trim();
            cleaned = cleaned.replaceAll("(?<=[가-힣])\\s+[A-Za-z]+$", "").trim();
            // 한글-라틴 중간 노이즈 제거 (컴 weeprepsy 퓨터 → 컴퓨터)
            cleaned = cleaned.replaceAll("(?<=[가-힣])\\s*[a-zA-Z]{2,}\\s*(?=[가-힣])", "").trim();
            if (cleaned.length() >= 2) sb.append(cleaned);
        }
        String result = sb.toString().trim();
        // 한글과 한글 사이에 끼어든 영문 노이즈 제거 (컴 weeprepsy 퓨터 → 컴퓨터)
        result = result.replaceAll("(?<=[가-힣])\\s*[a-zA-Z]{2,}\\s*(?=[가-힣])", "").trim();
        // 최종 결합 결과에 후행 영어 노이즈 제거 (여러 줄 연결 후 발생하는 "IIHHI" 등)
        result = result.replaceAll("\\s+\\d+$", "").trim();
        result = java.util.regex.Pattern.compile("\\s+[A-Z][A-Za-z]{2,}$")
                .matcher(result)
                .replaceAll(mr -> {
                    String word = mr.group().trim();
                    // OCR 'l'→'I' 정규화 후 로마숫자 판별
                    String normalized = word.replace('l', 'I');
                    if (normalized.matches("[IV]+")) return " " + normalized;
                    // 전체 대문자 케이스
                    if (word.chars().allMatch(c -> !Character.isLetter(c) || Character.isUpperCase(c))) {
                        // I/V 시작 → 로마숫자 앞부분만 유지 (IIHHI→II)
                        String roman = normalized.replaceAll("^([IV]+).*", "$1");
                        if (!roman.isEmpty()) return " " + roman;
                        return mr.group(); // 그 외 영어단어(ENGLISH) → 유지
                    }
                    // 혼합 대소문자 → OCR 노이즈 → 로마숫자 앞부분만 추출
                    String roman = normalized.replaceAll("^([IV]+).*", "$1");
                    return roman.isEmpty() ? "" : " " + roman;
                });
        result = result.trim();
        // 후행 비문자 기호 제거 (라인별 처리 후 남은 "." 등)
        result = result.replaceAll("[^가-힣a-zA-Z0-9()\\s]+$", "").trim();
        // 섹션번호 제거: (1), (2), (8) 등
        result = result.replaceAll("\\(\\d+\\)?$", "").trim();
        // 후행 소문자 토큰 제거 (cara ols 등 OCR 노이즈) — 먼저 제거해야 뒤따르는 Korean+Latin 검사가 작동
        result = result.replaceAll("(\\s+[a-z]{2,5})+$", "").trim();
        // 한글 뒤 후행 라틴/숫자/괄호 노이즈 반복 제거
        String prev;
        do {
            prev = result;
            // Korean 바로 뒤 Latin 세그먼트 제거: "시스s oO" → "시스" (공백 포함 연속 그룹 처리)
            result = result.replaceAll("(?<=[가-힣])[a-zA-Z]+(\\s+[a-zA-Z]+)*$", "").trim();
            result = result.replaceAll("(?<=[가-힣])\\s+[A-Za-z]+$", "").trim();
            result = result.replaceAll("(?<=[가-힣])\\(\\d+\\)?$", "").trim();
            result = result.replaceAll("(?<=[가-힣])\\d+$", "").trim();
        } while (!result.equals(prev));
        // 짝 안 맞는 후행 ')' 제거
        long opens = result.chars().filter(c -> c == '(').count();
        long closes = result.chars().filter(c -> c == ')').count();
        if (closes > opens) result = result.replaceAll("\\)$", "").trim();
        // 후행 내장 건물명 제거 (인문한국진흥관→제거, 농대4호관→제거)
        result = result.replaceAll("[가-힣]{1,2}\\d+호관$", "").trim();  // 농대4호관 style
        result = result.replaceAll("[가-힣]{2,6}관$", "").trim();        // 인문한국진흥관 style
        result = deduplicateText(result);
        return result;
    }

    // ── 블록 내 단어들 → 과목명 추출 ─────────────────────────────────────────

    static String buildSubjectName(List<Word> wordsIn) {
        if (wordsIn.isEmpty()) return "";

        List<Word> words = new ArrayList<>(wordsIn);
        words.sort(Comparator.comparingInt(w -> w.getBoundingBox().y + w.getBoundingBox().height / 2));

        // y 중심이 threshold(20px) 이내면 같은 줄로 묶기
        int threshold = 20;
        List<List<Word>> lines = new ArrayList<>();
        List<Word> cur = new ArrayList<>();
        int lastCY = -1000;
        for (Word w : words) {
            int cy = w.getBoundingBox().y + w.getBoundingBox().height / 2;
            if (lastCY >= 0 && Math.abs(cy - lastCY) > threshold) {
                lines.add(new ArrayList<>(cur)); cur.clear();
            }
            cur.add(w); lastCY = cy;
        }
        if (!cur.isEmpty()) lines.add(cur);

        StringBuilder sb = new StringBuilder();
        for (List<Word> line : lines) {
            line.sort(Comparator.comparingInt(w -> w.getBoundingBox().x));

            for (int wi = 0; wi < line.size(); wi++) {
                String t = line.get(wi).getText().trim();
                if (t.isEmpty()) continue;
                // 강의실/건물 코드 단어 → 이 단어부터 줄 끝까지 skip (break)
                if (ROOM_CODE.matcher(t).matches()
                    || t.contains("캠퍼스")
                    || t.matches(".*호관$")
                    || GPA_PATTERN.matcher(t).find()) break;
                if (t.matches("[^가-힣a-zA-Z0-9()]+")) continue; // 순수 기호
                if (t.matches("\\d{2,}")) continue;              // 2자리 이상 순수 숫자
                // 1-2자 라틴 노이즈: 소문자 포함(aU, Ey)만 제거, 대문자(II, IV)는 유지
                if (t.matches("[a-zA-Z]{1,2}") && t.matches(".*[a-z].*")) continue;
                // 단일 한국어: 바로 다음 단어가 강의실 코드인 경우만 노이즈로 제거 (공1302의 '공')
                // 과목명 마지막 글자('학', '해' 등)는 유지
                if (t.matches("[가-힣]")) {
                    boolean nextIsCode = wi + 1 < line.size() && (
                        ROOM_CODE.matcher(line.get(wi + 1).getText().trim()).matches()
                        || GPA_PATTERN.matcher(line.get(wi + 1).getText().trim()).find());
                    if (nextIsCode) continue;
                }
                if (sb.length() > 0) sb.append(" ");
                sb.append(t);
            }
        }
        String result = sb.toString().trim();
        // 한글과 한글 사이에 끼어든 영문 노이즈 제거 (컴 weeprepsy 퓨터 → 컴퓨터)
        result = result.replaceAll("(?<=[가-힣])\\s*[a-zA-Z]{2,}\\s*(?=[가-힣])", "").trim();
        // 선행 노이즈 제거 (숫자는 유지: 4차산업혁명, 3D프린팅 등)
        result = result.replaceAll("^[^가-힣a-zA-Z0-9]+", "").trim();
        // 후행 섹션번호 제거: (1, (2 등
        result = result.replaceAll("\\(\\d+\\)?$", "").trim();
        // 후행 OCR 노이즈: =I, |X, -- 등
        result = result.replaceAll("[|=\\-]{1,3}[A-Za-z0-9]{0,3}$", "").trim();
        result = result.replaceAll("[|=\\-~`'\"^*\\s]+$", "").trim();
        result = result.replaceAll("[^가-힣a-zA-Z0-9()\\s]+$", "").trim();
        // 영어 과목명 후행 노이즈: 공백+숫자, $3017 형식 방 코드, 공백+대문자자음클러스터 제거
        result = result.replaceAll("\\s+\\$?\\d+$", "").trim();
        result = java.util.regex.Pattern.compile("\\s+[A-Z][A-Za-z]{2,}$")
                .matcher(result)
                .replaceAll(mr -> {
                    String word = mr.group().trim();
                    // OCR 'l'→'I' 정규화 후 로마숫자 판별
                    String normalized = word.replace('l', 'I');
                    if (normalized.matches("[IV]+")) return " " + normalized;
                    // 전체 대문자 케이스
                    if (word.chars().allMatch(c -> !Character.isLetter(c) || Character.isUpperCase(c))) {
                        // I/V 시작 → 로마숫자 앞부분만 유지 (IIHHI→II)
                        String roman = normalized.replaceAll("^([IV]+).*", "$1");
                        if (!roman.isEmpty()) return " " + roman;
                        return mr.group(); // 그 외 영어단어(ENGLISH) → 유지
                    }
                    // 혼합 대소문자 → OCR 노이즈 → 로마숫자 앞부분만 추출
                    String roman = normalized.replaceAll("^([IV]+).*", "$1");
                    return roman.isEmpty() ? "" : " " + roman;
                });
        result = result.trim();
        // 한글 직후 라틴 노이즈 반복 제거 (s oO, soO, St, cara 등 모든 패턴 커버)
        String prevBuild;
        do {
            prevBuild = result;
            // 한글 바로 뒤에 붙은 Latin 세그먼트들 제거: "시스s oO" → "시스"
            result = result.replaceAll("(?<=[가-힣])[a-zA-Z]+(\\s+[a-zA-Z]+)*$", "").trim();
            // 한글 뒤 공백+Latin 제거: "야기 Soy" → "야기"
            result = result.replaceAll("(?<=[가-힣])\\s+[A-Za-z]+$", "").trim();
            // 한글 뒤 후행 숫자 제거: "영어회화2" → "영어회화"
            result = result.replaceAll("(?<=[가-힣])\\d+$", "").trim();
        } while (!result.equals(prevBuild));
        // 짝 안 맞는 후행 ')' 제거
        long opens = result.chars().filter(c -> c == '(').count();
        long closes = result.chars().filter(c -> c == ')').count();
        if (closes > opens) result = result.replaceAll("\\)$", "").trim();
        // 후행 내장 건물명 제거 (인문한국진흥관→제거, 농대4호관→제거)
        result = result.replaceAll("[가-힣]{1,2}\\d+호관$", "").trim();  // 농대4호관 style
        result = result.replaceAll("[가-힣]{2,6}관$", "").trim();        // 인문한국진흥관 style
        // 중복 연속 단어 제거
        result = deduplicateText(result);
        return result;
    }

    /** 공백으로 분리된 문자열에서 강의실 코드가 나오는 지점부터 잘라냄 */
    static String truncateAtRoomCode(String line) {
        StringBuilder sb = new StringBuilder();
        for (String w : line.split("\\s+")) {
            if (w.isEmpty()) continue;
            if (ROOM_CODE.matcher(w).matches()
                || w.contains("캠퍼스")
                || w.matches(".*호관$")
                || w.matches("\\d+/\\d+")          // 2/101, 3/201 형식 강의실 코드
                || w.matches("\\$\\d+")             // $3017 형식
                || GPA_PATTERN.matcher(w).find()) break;
            if (sb.length() > 0) sb.append(" ");
            sb.append(w);
        }
        return sb.toString().trim();
    }

    /** 연속 중복 텍스트 제거: "채플채플"→"채플", "채플(2채플"→"채플", "ABC ABC"→"ABC" */
    static String deduplicateText(String s) {
        if (s.length() < 3) return s;
        // 정확한 절반 반복: "채플채플"
        int half = s.length() / 2;
        if (s.length() % 2 == 0 && s.substring(0, half).equals(s.substring(half))) {
            return s.substring(0, half);
        }
        // 공백으로 분리된 반복: "ABC ABC" → "ABC"
        int mid = s.indexOf(' ');
        if (mid > 0) {
            String first = s.substring(0, mid);
            String rest  = s.substring(mid + 1);
            if (rest.startsWith(first)) return first + (rest.length() > first.length() ? " " + rest.substring(first.length()).trim() : "");
        }
        // 중간 노이즈 섞인 반복: "채플(2채플" → "채플"
        // 앞 N자가 뒤에 다시 나타나면 앞 N자만 반환
        for (int n = 2; n <= s.length() / 2; n++) {
            String prefix = s.substring(0, n);
            int idx = s.indexOf(prefix, n);
            if (idx >= n && idx <= s.length() - n) {
                return prefix;
            }
        }
        return s;
    }

    // ── 블록 x 중심 → 요일 매핑 ─────────────────────────────────────────────

    static String assignDay(Rectangle block, Map<String, Integer> dayColX) {
        if (dayColX.isEmpty()) return null;
        int cx = block.x + block.width / 2;
        String best = null; int bestDist = Integer.MAX_VALUE;
        for (Map.Entry<String,Integer> e : dayColX.entrySet()) {
            int d = Math.abs(cx - e.getValue());
            if (d < bestDist) { bestDist = d; best = e.getKey(); }
        }
        return best == null ? null : DAY_MAP.get(best);
    }

    // ── 블록 y 범위 → 시작/종료 시간 ────────────────────────────────────────

    static String[] assignTimes(Rectangle block, TreeMap<Integer, Integer> timeRowY) {
        if (timeRowY.size() < 2) return null;

        List<int[]> rows = new ArrayList<>();
        for (Map.Entry<Integer,Integer> e : timeRowY.entrySet())
            rows.add(new int[]{e.getKey(), e.getValue()});
        rows.sort(Comparator.comparingInt(a -> a[1]));

        double pixPerHour = (double)(rows.get(rows.size()-1)[1] - rows.get(0)[1])
                          / (rows.get(rows.size()-1)[0] - rows.get(0)[0]);
        if (pixPerHour <= 0) return null;
        int baseHour = rows.get(0)[0], baseY = rows.get(0)[1];

        double startFrac = baseHour + (block.y              - baseY) / pixPerHour;
        double endFrac   = baseHour + (block.y + block.height - baseY) / pixPerHour;

        return new String[]{ fracToTime(startFrac), fracToTime(endFrac) };
    }

    static int timeToMinutes(String hhmm) {
        String[] parts = hhmm.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    static String fracToTime(double frac) {
        int hour = (int) frac;
        double minFrac = (frac - hour) * 60;
        // 15분 단위로 반올림 (0, 15, 30, 45)
        int min = (int) (Math.round(minFrac / 15.0) * 15);
        if (min >= 60) { min = 0; hour++; }
        return String.format("%02d:%02d:00", Math.max(0, Math.min(23, hour)), min);
    }

    // ── 보조 함수들 ──────────────────────────────────────────────────────────

    /** allWords 중 특정 텍스트를 가진 첫 번째 단어의 y 반환 (dayColX dedup용) */
    static int getWordY(List<Word> words, String text) {
        return words.stream()
                .filter(w -> w.getText().trim().equals(text))
                .mapToInt(w -> w.getBoundingBox().y)
                .min().orElse(Integer.MAX_VALUE);
    }
}
