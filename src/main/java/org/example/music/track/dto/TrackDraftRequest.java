package org.example.music.track.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Schema(description = "歌曲草稿局部更新请求")
public class TrackDraftRequest {

    @NotNull
    @PositiveOrZero
    @Schema(description = "编辑前的歌曲版本号", example = "3")
    private Long version;

    @Size(max = 255)
    @Schema(description = "歌曲标题")
    private String title;

    @Size(max = 255)
    @Schema(description = "主艺术家名称")
    private String artistName;

    @Size(max = 255)
    @Schema(description = "专辑名称")
    private String albumName;

    @Schema(description = "曲序")
    private Integer trackNumber;

    @Schema(description = "碟片序号")
    private Integer discNumber;

    private boolean titlePresent;
    private boolean artistNamePresent;
    private boolean albumNamePresent;
    private boolean trackNumberPresent;
    private boolean discNumberPresent;

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getTitle() {
        return title;
    }

    @JsonProperty("title")
    public void setTitle(String title) {
        this.title = title;
        this.titlePresent = true;
    }

    public String getArtistName() {
        return artistName;
    }

    @JsonProperty("artistName")
    public void setArtistName(String artistName) {
        this.artistName = artistName;
        this.artistNamePresent = true;
    }

    public String getAlbumName() {
        return albumName;
    }

    @JsonProperty("albumName")
    public void setAlbumName(String albumName) {
        this.albumName = albumName;
        this.albumNamePresent = true;
    }

    public Integer getTrackNumber() {
        return trackNumber;
    }

    @JsonProperty("trackNumber")
    public void setTrackNumber(Integer trackNumber) {
        this.trackNumber = trackNumber;
        this.trackNumberPresent = true;
    }

    public Integer getDiscNumber() {
        return discNumber;
    }

    @JsonProperty("discNumber")
    public void setDiscNumber(Integer discNumber) {
        this.discNumber = discNumber;
        this.discNumberPresent = true;
    }

    public boolean hasTitle() {
        return titlePresent;
    }

    public boolean hasArtistName() {
        return artistNamePresent;
    }

    public boolean hasAlbumName() {
        return albumNamePresent;
    }

    public boolean hasTrackNumber() {
        return trackNumberPresent;
    }

    public boolean hasDiscNumber() {
        return discNumberPresent;
    }
}
