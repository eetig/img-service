package org.example.dto;

/**
 * 容器底图上传的结果：**一张进、两张出**。
 *
 * <p>前端拿到 {@code fileName} 后拼成 `/files/{fileName}` 存进
 * `equipment_ledger.image_file`；亮线版按 `-dark` 后缀约定自动拼出来
 * （见前端 `useVesselList.js` 的 darkVariantOf），所以这里只回基名 + 亮线版名，
 * 便于调用方核对两者确实成对。
 */
public class VesselDrawingResult {

    private String fileName;      // 白纸版（浅色主题用），如 abc123.png
    private String darkFileName;  // 亮线版（深色主题用），如 abc123-dark.png

    public VesselDrawingResult() {
    }

    public VesselDrawingResult(String fileName, String darkFileName) {
        this.fileName = fileName;
        this.darkFileName = darkFileName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getDarkFileName() {
        return darkFileName;
    }

    public void setDarkFileName(String darkFileName) {
        this.darkFileName = darkFileName;
    }
}
