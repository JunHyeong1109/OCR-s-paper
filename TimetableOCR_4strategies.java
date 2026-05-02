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
 * 시간표 이미지 → JSON 변환기
 *
 * 개선된 흐름:
 *   1단계: 좁은 스트립 OCR → 요일/시간 구조만 파악 (전체 이미지 OCR 제거)
 *   2단계: 색 기반 BFS → 과목 블록 검출
 *   3단계: 블록 crop → 지배색 전처리 → Tesseract (문자 인식만 담당)
 */
public class TimetableOCR {

    private static final Map<String, String> DAY_MAP = new LinkedHashMap<>();
    private static final Set<String> TIME_LABELS =
            new HashSet<>(Arrays.asList("8","9","10","11","12","1","2","3","4","5","6","7"));

    private static final Pattern ROOM_CODE = Pattern.compile(
        "^[가-힣]{1,4}\\d{2,5}$" +
        "|^[가-힣]{1,4}\\d{2,5}-\\d{1,4}$" +
        "|^[a-zA-Z]{1,3}\\d{1,4}$" +
        "|^[a-zA-Z]{1,2}\\d{1,3}-\\d{1,4}$" +
        "|^\\d{3,5}$" +
        "|^\\d+호$" +
        "|^[가-힣]{2,6}관$" +
        "|^[가-힣]{2,6}호관$" +
        "|^[가-힣]{1,2}\\d+호관$" +
        "|^\\d+/\\d+$"
    );

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
        System.setOut(new java.io.PrintStream(System.out, true, "UTF-8"));
        System.setErr(new java.io.PrintStream(System.err, true, "UTF-8"));
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

        boolean darkMode = isImageDark(image);
        BufferedImage ocrImage = darkMode ? invertImage(image) : image;

        Tesseract tess = buildTesseract();

        // ── 1단계: 좁은 스트립 OCR → 구조 분석 (전체 이미지 OCR 없음) ───────────
        System.err.println("[1/3] 레이아웃 분석 중... (darkMode=" + darkMode + ")");

        // 요일 헤더: 상단 스트립 OCR — 25% → 실패 시 50%까지 확장
        Map<String, Rectangle> dayHeaderBoxes = new LinkedHashMap<>();
        int[] headerRatios = {4, 3, 2}; // 1/4, 1/3, 1/2
        for (int ratio : headerRatios) {
            int headerH = Math.max(40, image.getHeight() / ratio);
            headerH = Math.min(headerH, ocrImage.getHeight());
            BufferedImage topStrip = ocrImage.getSubimage(0, 0, ocrImage.getWidth(), headerH);
            tess.setLanguage("kor+eng");
            List<Word> headerWords = tess.getWords(topStrip, ITessAPI.TessPageIteratorLevel.RIL_WORD);
            for (Word w : headerWords) {
                String t = w.getText().trim();
                Rectangle b = w.getBoundingBox();
                if (DAY_MAP.containsKey(t)) {
                    if (!dayHeaderBoxes.containsKey(t) || b.y < dayHeaderBoxes.get(t).y)
                        dayHeaderBoxes.put(t, b);
                }
            }
            if (dayHeaderBoxes.size() >= 2) break; // 2개 이상 찾으면 확장 중단
        }
        tess.setLanguage("kor+eng");

        Map<String, Integer> dayColX = new TreeMap<>();
        int dayHeaderMaxY = 0;
        for (Map.Entry<String, Rectangle> e : dayHeaderBoxes.entrySet()) {
            Rectangle b = e.getValue();
            dayColX.put(e.getKey(), b.x + b.width / 2);
            dayHeaderMaxY = Math.max(dayHeaderMaxY, b.y + b.height);
        }
        if (dayColX.isEmpty()) System.err.println("  경고: 요일 헤더를 찾지 못했습니다.");
        System.err.println("  요일 컬럼: " + dayColX.keySet());

        int bfsTopBound = dayHeaderMaxY > 0 ? dayHeaderMaxY + 5 : image.getHeight() / 20;
        int timeColW = computeTimeColWidth(dayColX, image.getWidth());

        // 시간 레이블: 왼쪽 좁은 스트립만 OCR
        int timeStripW = Math.min(timeColW, image.getWidth() / 12);
        TreeMap<Integer, Integer> timeRowY = new TreeMap<>();
        BufferedImage leftStrip = ocrImage.getSubimage(0, 0, timeStripW, ocrImage.getHeight());
        tess.setLanguage("eng");
        List<Word> leftWords = tess.getWords(leftStrip, ITessAPI.TessPageIteratorLevel.RIL_WORD);
        tess.setLanguage("kor+eng");

        int minTimeY = bfsTopBound;
        for (Word w : leftWords) {
            String t = w.getText().trim();
            Rectangle b = w.getBoundingBox();
            if (b.y > minTimeY && TIME_LABELS.contains(t)) {
                int actual = toActualHour(Integer.parseInt(t));
                timeRowY.putIfAbsent(actual, b.y);
            }
        }

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
        int bfsMinBrightness = darkMode ? 30 : 60;
        int bfsMinSat = darkMode ? 6 : 20;
        List<Rectangle> blocks = detectColoredBlocks(image, 0, bfsTopBound, minBlockPixels, bfsMinBrightness, bfsMinSat);

        // timeColW 보정: 블록 좌측 끝 기준
        if (!blocks.isEmpty()) {
            int leftmostX = blocks.stream().mapToInt(b -> b.x).min().orElse(timeColW);
            if (leftmostX < timeColW - 10)
                timeColW = Math.max(20, leftmostX - 5);
        }

        // 미검출 요일 컬럼 추론
        if (dayColX.size() >= 2 && !blocks.isEmpty()) {
            List<String> DAY_ORDER = Arrays.asList("월", "화", "수", "목", "금", "토");
            int minDayX2 = dayColX.values().stream().mapToInt(Integer::intValue).min().orElse(0);
            int maxDayX2 = dayColX.values().stream().mapToInt(Integer::intValue).max().orElse(image.getWidth());
            int colWidth2 = (maxDayX2 - minDayX2) / (dayColX.size() - 1);
            final int capturedTimeColW = timeColW;
            int leftThreshold = minDayX2 - colWidth2 / 2;
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

        // 시간표 경계 계산
        final int finalTimeColW = timeColW;
        int rightBound = image.getWidth();
        if (dayColX.size() >= 2) {
            int minDayX = dayColX.values().stream().mapToInt(Integer::intValue).min().orElse(0);
            int maxDayX = dayColX.values().stream().mapToInt(Integer::intValue).max().orElse(image.getWidth());
            int colWidth = (maxDayX - minDayX) / (dayColX.size() - 1);
            rightBound = maxDayX + colWidth + colWidth / 2;
        }

        int bottomTimeBound = image.getHeight() - image.getHeight() / 25;
        if (timeRowY.size() >= 2) {
            List<int[]> trows = new ArrayList<>();
            timeRowY.forEach((h, y) -> trows.add(new int[]{h, y}));
            trows.sort(Comparator.comparingInt(a -> a[1]));
            double pph = (double)(trows.get(trows.size()-1)[1] - trows.get(0)[1])
                       / (trows.get(trows.size()-1)[0] - trows.get(0)[0]);
            bottomTimeBound = trows.get(trows.size()-1)[1] + (int)(pph * 1.5);
        }

        if (timeRowY.size() < 2 && !blocks.isEmpty()) {
            System.err.println("  시간 레이블 검출 실패 - 블록 위치로 시간 그리드 추정");
            int contentTop = bfsTopBound;
            int contentBottom = bottomTimeBound;
            int contentHeight = contentBottom - contentTop;
            int startHour = 9, totalHours = 9;
            double pph = (double) contentHeight / totalHours;
            for (int h = 0; h < totalHours; h++)
                timeRowY.put(startHour + h, contentTop + (int)(h * pph));
            System.err.println("  추정 그리드: " + startHour + "시~" + (startHour + totalHours) + "시, " + (int)pph + "px/h");
        }

        blocks.removeIf(b -> b.x + b.width < finalTimeColW + 5);
        final int finalRightBound = rightBound;
        blocks.removeIf(b -> b.x + b.width / 2 > finalRightBound);
        final int finalBottomBound = bottomTimeBound;
        blocks.removeIf(b -> b.y + b.height / 2 > finalBottomBound);
        System.err.println("  " + blocks.size() + "개 블록 발견");

        // ── 3단계: 블록별 crop → 지배색 전처리 → Tesseract ──────────────────
        System.err.println("[3/3] 블록별 OCR 중...");
        List<Map<String, String>> schedules = new ArrayList<>();

        for (Rectangle block : blocks) {
            String subject = primaryBlockOCR(tess, image, ocrImage, block, darkMode);
            if (subject.isEmpty() || subject.length() < 2) continue;
            if (subject.matches("[a-zA-Z]{1,4}")) continue;
            if (GPA_PATTERN.matcher(subject).find()) continue;
            if (subject.matches("[\\d\\s/\\\\.,+\\-=|@#$%^&*()]+")) continue;
            if (subject.matches(".*[\\]\\[|{}].*")) continue;
            if (subject.matches("[a-z]\\s*\\(.*")) continue;

            String   day   = assignDay(block, dayColX);
            if (day == null) continue;

            String[] times = assignTimes(block, timeRowY);
            if (times == null) continue;
            if (times[0].equals(times[1])) continue;
            if (timeToMinutes(times[1]) - timeToMinutes(times[0]) < 45) continue;

            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("dayOfWeek",   day);
            entry.put("startTime",   times[0]);
            entry.put("endTime",     times[1]);
            entry.put("subjectName", subject.replaceAll("\\s+", ""));
            schedules.add(entry);
        }

        // 과목명 정규화: 접두어 통일
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
                if (shorter.length() >= 4 && longer.startsWith(shorter))
                    normalize.put(shorter, longer);
            }
        }
        if (!normalize.isEmpty())
            for (Map<String, String> entry : schedules) {
                String s = entry.get("subjectName");
                if (normalize.containsKey(s)) entry.put("subjectName", normalize.get(s));
            }

        // 중복 제거
        Set<String> seen = new LinkedHashSet<>();
        schedules.removeIf(m -> !seen.add(
                m.get("dayOfWeek") + "|" + m.get("startTime") + "|" + m.get("subjectName")));

        // OCR 노이즈 과목명 제거
        {
            Map<String, Set<String>> subjectTimes = new java.util.HashMap<>();
            for (Map<String, String> m : schedules) {
                String subj = m.get("subjectName");
                int korChars = (int) subj.chars().filter(c -> c >= 0xAC00 && c <= 0xD7A3).count();
                if (korChars >= 1 && korChars <= 2)
                    subjectTimes.computeIfAbsent(subj, k -> new java.util.HashSet<>())
                                .add(m.get("startTime"));
            }
            Set<String> noiseSubjects = new java.util.HashSet<>();
            for (Map.Entry<String, Set<String>> e : subjectTimes.entrySet())
                if (e.getValue().size() >= 2) noiseSubjects.add(e.getKey());
            if (!noiseSubjects.isEmpty()) {
                System.err.println("  OCR 노이즈 과목명 제거: " + noiseSubjects);
                schedules.removeIf(m -> noiseSubjects.contains(m.get("subjectName")));
            }
        }

        // 정렬
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

    // ── 지배색 기반 전처리 ────────────────────────────────────────────────────

    /**
     * 블록의 지배 배경색을 감지하고, 배경색과 다른 픽셀(텍스트)을 검정으로,
     * 배경색과 유사한 픽셀을 흰색으로 변환.
     *
     * 전체 이미지 이진화 대신 블록 단위로 적용하므로 각 블록의 색상에 최적화됨.
     */
    static BufferedImage preprocessByDominantColor(BufferedImage src, int x, int y, int w, int h) {
        int[] rgbPixels = new int[w * h];
        src.getSubimage(x, y, w, h).getRGB(0, 0, w, h, rgbPixels, 0, w);

        // 색상 히스토그램 (32 단위 양자화로 유사 색상 통합)
        Map<Integer, Integer> colorCount = new HashMap<>();
        for (int rgb : rgbPixels) {
            int qr = ((rgb >> 16) & 0xFF) / 32 * 32;
            int qg = ((rgb >>  8) & 0xFF) / 32 * 32;
            int qb = ( rgb        & 0xFF) / 32 * 32;
            colorCount.merge((qr << 16) | (qg << 8) | qb, 1, Integer::sum);
        }

        // 지배색: 순수 흰색/검정 제외하고 가장 많이 등장한 색 = 배경색
        int dominant = colorCount.entrySet().stream()
            .filter(e -> {
                int r = (e.getKey() >> 16) & 0xFF;
                int g = (e.getKey() >>  8) & 0xFF;
                int b =  e.getKey()        & 0xFF;
                int brightness = (r + g + b) / 3;
                return brightness > 40 && brightness < 230;
            })
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(-1);

        if (dominant == -1) {
            // 지배색 감지 실패 → 원본 crop 그대로 반환
            BufferedImage crop = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            crop.setRGB(0, 0, w, h, rgbPixels, 0, w);
            return crop;
        }

        int dr = (dominant >> 16) & 0xFF;
        int dg = (dominant >>  8) & 0xFF;
        int db =  dominant        & 0xFF;

        // 배경색과 거리가 먼 픽셀 = 텍스트 → 검정, 가까운 픽셀 = 배경 → 흰색
        int[] bin = new int[w * h];
        for (int i = 0; i < rgbPixels.length; i++) {
            int r = (rgbPixels[i] >> 16) & 0xFF;
            int g = (rgbPixels[i] >>  8) & 0xFF;
            int b =  rgbPixels[i]        & 0xFF;
            int dist = Math.abs(r - dr) + Math.abs(g - dg) + Math.abs(b - db);
            bin[i] = dist > 80 ? 0x000000 : 0xFFFFFF;
        }

        // 검정 픽셀이 절반 초과면 배경/텍스트가 뒤바뀐 것 → 반전
        long blackCount = 0;
        for (int p : bin) if (p == 0x000000) blackCount++;
        if (blackCount > (long) bin.length / 2)
            for (int i = 0; i < bin.length; i++)
                bin[i] = (bin[i] == 0x000000) ? 0xFFFFFF : 0x000000;

        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, w, h, bin, 0, w);
        return result;
    }

    // ── Sauvola 적응형 이진화 ─────────────────────────────────────────────────

    /**
     * Sauvola 적응형 이진화: 픽셀 주변 윈도우마다 개별 임계값 계산.
     * 인티그럴 이미지로 O(n) 처리.
     * T(x,y) = mean * (1 + k * (std / R - 1))
     */
    static BufferedImage preprocessBySauvola(BufferedImage src, int x, int y, int w, int h) {
        // 1) 원본 crop을 bicubic으로 2배 업스케일 → Sauvola 지역 통계 정밀도 향상
        int upW = w * 2, upH = h * 2;
        BufferedImage upscaled = new BufferedImage(upW, upH, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D ug = upscaled.createGraphics();
        ug.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        ug.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        ug.drawImage(src.getSubimage(x, y, w, h), 0, 0, upW, upH, null);
        ug.dispose();

        // 2) 업스케일된 이미지에서 그레이스케일 변환
        int[] gray = new int[upW * upH];
        for (int j = 0; j < upH; j++)
            for (int i = 0; i < upW; i++) {
                int rgb = upscaled.getRGB(i, j);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                gray[j * upW + i] = (r * 299 + g * 587 + b * 114) / 1000;
            }
        w = upW; h = upH;

        // 윈도우 크기: 이미지 단변의 1/5, 최소 15, 항상 홀수
        int winSize = Math.max(15, Math.min(w, h) / 5);
        if (winSize % 2 == 0) winSize++;
        int half = winSize / 2;
        double k = 0.3, R = 128.0;

        // 인티그럴 이미지 (합 / 제곱합)
        long[] iSum   = new long[(w + 1) * (h + 1)];
        long[] iSqSum = new long[(w + 1) * (h + 1)];
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) {
                long v = gray[j * w + i];
                iSum  [(j+1)*(w+1)+(i+1)] = v    + iSum  [j*(w+1)+(i+1)] + iSum  [(j+1)*(w+1)+i] - iSum  [j*(w+1)+i];
                iSqSum[(j+1)*(w+1)+(i+1)] = v*v  + iSqSum[j*(w+1)+(i+1)] + iSqSum[(j+1)*(w+1)+i] - iSqSum[j*(w+1)+i];
            }

        int[] bin = new int[w * h];
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) {
                int x1 = Math.max(0, i - half), y1 = Math.max(0, j - half);
                int x2 = Math.min(w-1, i + half), y2 = Math.min(h-1, j + half);
                int cnt = (x2-x1+1) * (y2-y1+1);
                long s  = iSum  [(y2+1)*(w+1)+(x2+1)] - iSum  [y1*(w+1)+(x2+1)] - iSum  [(y2+1)*(w+1)+x1] + iSum  [y1*(w+1)+x1];
                long sq = iSqSum[(y2+1)*(w+1)+(x2+1)] - iSqSum[y1*(w+1)+(x2+1)] - iSqSum[(y2+1)*(w+1)+x1] + iSqSum[y1*(w+1)+x1];
                double mean = (double) s / cnt;
                double std  = Math.sqrt(Math.max(0, (double) sq / cnt - mean * mean));
                double threshold = mean * (1.0 + k * (std / R - 1.0));
                bin[j * w + i] = gray[j * w + i] < threshold ? 0x000000 : 0xFFFFFF;
            }

        // 검정 과반이면 텍스트/배경 반전
        long blackCnt = 0;
        for (int p : bin) if (p == 0x000000) blackCnt++;
        if (blackCnt > (long) bin.length / 2)
            for (int i = 0; i < bin.length; i++)
                bin[i] = (bin[i] == 0x000000) ? 0xFFFFFF : 0x000000;

        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, w, h, bin, 0, w);
        return result;
    }

    // ── 블록 단위 OCR (메인 경로) ─────────────────────────────────────────────

    static String primaryBlockOCR(Tesseract tess, BufferedImage original,
                                   BufferedImage ocrImage, Rectangle block, boolean darkMode) {
        String best = "";
        int bestKor = 0;

        int x = Math.max(0, block.x);
        int y = Math.max(0, block.y);
        int w = Math.min(original.getWidth()  - x, block.width);
        int h = Math.min(original.getHeight() - y, block.height);
        if (w < 5 || h < 5) return "";

        // 전략 1: 지배색 기반 전처리
        try {
            BufferedImage preprocessed = preprocessByDominantColor(original, x, y, w, h);
            String r = ocrBlock(tess, preprocessed);
            int kor = korCount(r);
            if (kor > bestKor || (kor == bestKor && !r.isEmpty()
                    && (best.isEmpty() || r.length() < best.length()))) {
                best = r; bestKor = kor;
            }
        } catch (Exception ignored) {}

        // 전략 2: RGB 채도 기반 흰 텍스트 추출
        try {
            int[] rgbPixels = new int[w * h];
            original.getSubimage(x, y, w, h).getRGB(0, 0, w, h, rgbPixels, 0, w);
            int[] bin = new int[w * h];
            for (int i = 0; i < rgbPixels.length; i++) {
                int r = (rgbPixels[i] >> 16) & 0xFF;
                int g = (rgbPixels[i] >>  8) & 0xFF;
                int b =  rgbPixels[i]        & 0xFF;
                int brightness = (r + g + b) / 3;
                int sat = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                bin[i] = (brightness > 160 && sat < 60) ? 0x000000 : 0xFFFFFF;
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

        // 전략 3: Otsu 이진화
        try {
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

        // 전략 4: 다크모드 전용 - 밝기 상위 20% 텍스트 추출
        if (darkMode && bestKor == 0) {
            try {
                BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
                java.awt.Graphics2D gg = gray.createGraphics();
                gg.drawImage(original.getSubimage(x, y, w, h), 0, 0, null);
                gg.dispose();
                int[] gp = new int[w * h];
                gray.getRaster().getPixels(0, 0, w, h, gp);
                int[] sorted = gp.clone();
                Arrays.sort(sorted);
                int textThreshold = Math.max(sorted[(int)(sorted.length * 0.80)], 150);
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

    // ── 다크모드 감지 ────────────────────────────────────────────────────────

    static BufferedImage loadImage(String path) throws Exception {
        BufferedImage img = ImageIO.read(new File(path));
        if (img == null) return null;
        if (img.getType() == BufferedImage.TYPE_INT_RGB) return img;
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

    // ── 컬러 블록 BFS 검출 ────────────────────────────────────────────────────

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

    static boolean isColored(int rgb, int minBrightness, int minSat) {
        int r = (rgb>>16)&0xFF, g = (rgb>>8)&0xFF, b = rgb&0xFF;
        int brightness = (r + g + b) / 3;
        int sat = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
        return brightness > minBrightness && brightness < 225 && sat > minSat;
    }

    static boolean isColored(int rgb, int minBrightness) { return isColored(rgb, minBrightness, 20); }
    static boolean isColored(int rgb) { return isColored(rgb, 60, 20); }

    // ── 블록 OCR 내부 (확대 + 패딩 + 멀티 PSM) ───────────────────────────────

    private static String ocrBlock(Tesseract tess, BufferedImage img) throws Exception {
        int[] psms = { ITessAPI.TessPageSegMode.PSM_SINGLE_BLOCK,
                       ITessAPI.TessPageSegMode.PSM_SPARSE_TEXT,
                       ITessAPI.TessPageSegMode.PSM_SINGLE_LINE };
        String best = "";
        int bestKor = 0;
        for (int scale : new int[]{3, 4}) {
            if (scale == 4 && bestKor > 0) break;
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

    static int computeOtsuThreshold(int[] pixels) {
        int[] hist = new int[256];
        for (int p : pixels) hist[p & 0xFF]++;
        int total = pixels.length;
        double sum = 0;
        for (int i = 0; i < 256; i++) sum += i * hist[i];
        double sumB = 0, wB = 0, maxVar = 0;
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

    // ── 텍스트 후처리 ────────────────────────────────────────────────────────

    static String cleanFallbackText(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("[\\n\\r]+")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (GPA_PATTERN.matcher(t).find()) continue;
            if (t.contains("캠퍼스") || t.endsWith("호관")) continue;
            if (ROOM_CODE.matcher(t).matches()) continue;
            String truncated = truncateAtRoomCode(t);
            if (truncated.isEmpty()) continue;
            String cleaned = truncated;
            cleaned = cleaned.replaceAll("^[^가-힣a-zA-Z0-9]+", "").trim();
            cleaned = cleaned.replaceAll("\\(\\d+\\)?$", "").trim();
            cleaned = cleaned.replaceAll("[|=\\-]{1,3}[A-Za-z0-9]{0,3}$", "").trim();
            cleaned = cleaned.replaceAll("[|=\\-~`'\"^*\\s]+$", "").trim();
            cleaned = cleaned.replaceAll("[^가-힣a-zA-Z0-9()\\s]+$", "").trim();
            cleaned = cleaned.replaceAll("\\s+\\$?\\d+$", "").trim();
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
            cleaned = cleaned.replaceAll("(?<=[가-힣])[a-zA-Z]+(\\s+[a-zA-Z]+)*$", "").trim();
            cleaned = cleaned.replaceAll("(?<=[가-힣])\\s+[A-Za-z]+$", "").trim();
            cleaned = cleaned.replaceAll("(?<=[가-힣])\\s*[a-zA-Z]{2,}\\s*(?=[가-힣])", "").trim();
            if (cleaned.length() >= 2) sb.append(cleaned);
        }
        String result = sb.toString().trim();
        result = result.replaceAll("(?<=[가-힣])\\s*[a-zA-Z]{2,}\\s*(?=[가-힣])", "").trim();
        result = result.replaceAll("\\s+\\d+$", "").trim();
        result = java.util.regex.Pattern.compile("\\s+[A-Z][A-Za-z]{2,}$")
                .matcher(result)
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
        result = result.trim();
        result = result.replaceAll("[^가-힣a-zA-Z0-9()\\s]+$", "").trim();
        result = result.replaceAll("\\(\\d+\\)?$", "").trim();
        result = result.replaceAll("(\\s+[a-z]{2,5})+$", "").trim();
        String prev;
        do {
            prev = result;
            result = result.replaceAll("(?<=[가-힣])[a-zA-Z]+(\\s+[a-zA-Z]+)*$", "").trim();
            result = result.replaceAll("(?<=[가-힣])\\s+[A-Za-z]+$", "").trim();
            result = result.replaceAll("(?<=[가-힣])\\(\\d+\\)?$", "").trim();
            result = result.replaceAll("(?<=[가-힣])\\d+$", "").trim();
        } while (!result.equals(prev));
        long opens = result.chars().filter(c -> c == '(').count();
        long closes = result.chars().filter(c -> c == ')').count();
        if (closes > opens) result = result.replaceAll("\\)$", "").trim();
        result = result.replaceAll("[가-힣]{1,2}\\d+호관$", "").trim();
        result = result.replaceAll("[가-힣]{2,6}관$", "").trim();
        result = deduplicateText(result);
        return result;
    }

    static String truncateAtRoomCode(String line) {
        StringBuilder sb = new StringBuilder();
        for (String w : line.split("\\s+")) {
            if (w.isEmpty()) continue;
            if (ROOM_CODE.matcher(w).matches()
                || w.contains("캠퍼스")
                || w.matches(".*호관$")
                || w.matches("\\d+/\\d+")
                || w.matches("\\$\\d+")
                || GPA_PATTERN.matcher(w).find()) break;
            if (sb.length() > 0) sb.append(" ");
            sb.append(w);
        }
        return sb.toString().trim();
    }

    static String deduplicateText(String s) {
        if (s.length() < 3) return s;
        int half = s.length() / 2;
        if (s.length() % 2 == 0 && s.substring(0, half).equals(s.substring(half)))
            return s.substring(0, half);
        int mid = s.indexOf(' ');
        if (mid > 0) {
            String first = s.substring(0, mid);
            String rest  = s.substring(mid + 1);
            if (rest.startsWith(first))
                return first + (rest.length() > first.length() ? " " + rest.substring(first.length()).trim() : "");
        }
        for (int n = 2; n <= s.length() / 2; n++) {
            String prefix = s.substring(0, n);
            int idx = s.indexOf(prefix, n);
            if (idx >= n && idx <= s.length() - n) return prefix;
        }
        return s;
    }

    // ── 블록 → 요일/시간 매핑 ────────────────────────────────────────────────

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
        double startFrac = baseHour + (block.y               - baseY) / pixPerHour;
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
        int min = (int) (Math.round(minFrac / 15.0) * 15);
        if (min >= 60) { min = 0; hour++; }
        return String.format("%02d:%02d:00", Math.max(0, Math.min(23, hour)), min);
    }
}
