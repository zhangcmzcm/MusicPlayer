package org.example.music.favorite.exception;

public class FavoriteListValidationException extends RuntimeException {
    public FavoriteListValidationException() {
        super("收藏列表查询参数无效");
    }
}
