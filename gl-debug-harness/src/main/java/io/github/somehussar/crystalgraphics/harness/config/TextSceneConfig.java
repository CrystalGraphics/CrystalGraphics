package io.github.somehussar.crystalgraphics.harness.config;

import io.github.somehussar.crystalgraphics.text.msdf.CgMsdfAtlasConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed configuration for the text-scene.
 */
public class TextSceneConfig extends HarnessConfig {

    String kanji = "ブリキノダンス\n" +
        "さあ 憐れんで 血統書 持ち寄って反教典\n" +
        "沈んだ唱導 腹這い幻聴\n" +
        "謁見 席巻 妄信症\n" +
        "踊れ酔え孕め アヴァターラ新大系\n" +
        "斜めの幻聴 錻力と宗教\n" +
        "ラル・ラリ・唱えろ生\n" +
        "まあ 逆らって新王都 くぐもった脳系統\n" +
        "墓掘れ説法 釈迦釈迦善行\n" +
        "六感・吶喊・竜胆・錠\n" +
        "どれどれ 震え 蔑さげすんで新体系\n" +
        "欺瞞の延長 詭弁の劣等\n" +
        "ドグ・ラグ・叶えろ\n" +
        "不気味な手 此処に在り\n" +
        "理性の目 咽び泣き\n" +
        "踵返せ遠くに\n" +
        "偲ぶ君の瞳を\n" +
        "さあ 皆舞いな 空洞で\n" +
        "サンスクリット求道系 抉り抜いた鼓動\n" +
        "咲かせ咲かせ\n" +
        "さあ 剽悍な双眸を エーカム そうさ 先頭に\n" +
        "真っ赤に濡れた空 踵鳴らせ\n" +
        "嗚呼 漠然と運命星\n" +
        "重度に負った喘鳴に\n" +
        "優劣等無いさ回れ踊れ\n" +
        "もう 漠然と九番目が龍を薙ぐ\n" +
        "パッパラ・ラル・ラリ ブリキノダンス\n" +
        "さあ 微笑んで急展開 ナラシンハ流体系\n" +
        "積もった信仰 惜別劣等\n" +
        "怨恨 霊堂 脳震盪\n" +
        "パラパラ狂え アヴァターラ半酩酊\n" +
        "次第に昏倒\n" +
        "劣悪情動 崇めろバララーマ\n" +
        "死んでる龍が吼える バガヴァッド・ギーターで\n" +
        "張り詰め心臓\n" +
        "押し引け問答 無に帰す桃源郷\n" +
        "ドウドウ唸れ アヴァターラ封筒へ\n" +
        "クリシュナ誘導 アルジュナ引導\n" +
        "ドグ・ラグ・祝えや\n" +
        "不気味な手 誠なり\n" +
        "理解などとうに無き\n" +
        "鬼神討てよ遠くに\n" +
        "潜む影の手引きを\n" +
        "さあ皆 舞な 衝動で\n" +
        "サンスクリット求道系 雑多に暮れた日々\n" +
        "廃れ 廃れ\n" +
        "さあ剽悍な双眸で\n" +
        "サプタの脳が正統系\n" +
        "真っ赤に塗れた空\n" +
        "響け響け\n" +
        "嗚呼 六芒と流線型\n" +
        "王族嫌悪は衝動性\n" +
        "ピンチにヒットな祝詞 ハバケ・ルドレ\n" +
        "もう 漠然と九番目が狂を急ぐ\n" +
        "巷で噂の ブリキノダンス\n" +
        "さあ 皆舞いな 空洞で\n" +
        "サンスクリット求道系 抉り抜いた鼓動\n" +
        "咲かせ燃やせ\n" +
        "さあ 剽悍な双眸を エーカム そうさ 先頭で\n" +
        "全く以て 鼓動がダンス\n" +
        "王手を盗って遠雷帝\n" +
        "サンスクリット求道系 妄想信者踊る\n" +
        "酷く脆く\n" +
        "もう 漠然と九番目が盲如く\n" +
        "御手々を拝借 ブリキノダンス\n" +
        "Translate to English";

    private String text = kanji;//"CrystalGraphics font demo - mouse wheel zoom بيانات الاستفسار";
    private int atlasSize = 512;
    private int fontSizePx = 24;
    private boolean dumpBitmapAtlas = true;
    private float poseScale = 1.0f;
    private int guiScale = 1;
    private List<Float> scales = new ArrayList<Float>();
    private String outputFilename = null;
    private boolean mtsdf = CgMsdfAtlasConfig.DEFAULT_MTSDF;

    public String getText() { return text; }
    public int getAtlasSize() { return atlasSize; }
    public int getFontSizePx() { return fontSizePx; }
    public boolean isDumpBitmapAtlas() { return dumpBitmapAtlas; }
    public float getPoseScale() { return poseScale; }
    public int getGuiScale() { return guiScale; }
    public String getOutputFilename() { return outputFilename; }
    public boolean isMtsdf() { return mtsdf; }

    public CgMsdfAtlasConfig buildMsdfAtlasConfig() {
        return CgMsdfAtlasConfig.defaultConfig().withPageSize(atlasSize).withMtsdf(mtsdf);
    }

    /**
     * Returns the list of scales for multi-scale comparison rendering.
     * If {@code --scales} was specified, returns those values.
     * Otherwise, returns a single-element list containing {@code poseScale}.
     */
    public List<Float> getEffectiveScales() {
        if (!scales.isEmpty()) {
            return scales;
        }
        List<Float> single = new ArrayList<Float>();
        single.add(poseScale);
        return single;
    }

    /** Returns true if multi-scale comparison mode is active. */
    public boolean isMultiScaleMode() {
        return scales.size() > 1;
    }

    @Override
   public void applySystemProperties() {
        super.applySystemProperties();
        String fs = System.getProperty("harness.font.size.px");
        if (fs != null && !fs.isEmpty()) {
            this.fontSizePx = parseIntStrict(fs, "harness.font.size.px");
        }
        String as = System.getProperty("harness.atlas.size");
        if (as != null && !as.isEmpty()) {
            this.atlasSize = parseIntStrict(as, "harness.atlas.size");
        }
        String mtsdfProp = System.getProperty("harness.mtsdf");
        if (mtsdfProp != null && !mtsdfProp.isEmpty()) {
            this.mtsdf = "true".equalsIgnoreCase(mtsdfProp);
        }
    }

    @Override
   public void applyCliArgs(Map<String, String> args) {
        super.applyCliArgs(args);
        if (args.containsKey("text")) {
            this.text = args.get("text");
        }
        if (args.containsKey("atlas-size")) {
            this.atlasSize = parseIntStrict(args.get("atlas-size"), "--atlas-size");
        }
        if (args.containsKey("font-size-px")) {
            this.fontSizePx = parseIntStrict(args.get("font-size-px"), "--font-size-px");
        }
        if (args.containsKey("dump-bitmap-atlas")) {
            this.dumpBitmapAtlas = "true".equalsIgnoreCase(args.get("dump-bitmap-atlas"));
        }
        if (args.containsKey("pose-scale")) {
            this.poseScale = parseFloatStrict(args.get("pose-scale"), "--pose-scale");
        }
        if (args.containsKey("scales")) {
            this.scales = parseFloatList(args.get("scales"), "--scales");
        }
        if (args.containsKey("output-filename")) {
            this.outputFilename = args.get("output-filename");
        }
        if (args.containsKey("gui-scale")) {
            this.guiScale = parseIntStrict(args.get("gui-scale"), "--gui-scale");
            if (this.guiScale < 1) {
                throw new IllegalArgumentException("--gui-scale must be >= 1, got: " + this.guiScale);
            }
        }
        if (args.containsKey("mtsdf")) {
            this.mtsdf = "true".equalsIgnoreCase(args.get("mtsdf"));
        }
    }

    public static TextSceneConfig create(String[] args) {
        TextSceneConfig cfg = new TextSceneConfig();
        cfg.applySystemProperties();
        cfg.applyCliArgs(HarnessConfig.parseCliArgs(args));
        return cfg;
    }

    static float parseFloatStrict(String value, String paramName) {
        try {
            float f = Float.parseFloat(value);
            if (Float.isNaN(f) || Float.isInfinite(f)) {
                throw new IllegalArgumentException(
                    "Invalid value for " + paramName + ": '" + value + "' (must be a finite number)");
            }
            return f;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid value for " + paramName + ": '" + value + "' (must be a valid float)");
        }
    }

    static List<Float> parseFloatList(String value, String paramName) {
        List<Float> result = new ArrayList<Float>();
        String[] parts = value.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(parseFloatStrict(trimmed, paramName));
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid value for " + paramName + ": '" + value + "' (must contain at least one scale)");
        }
        return result;
    }
}
