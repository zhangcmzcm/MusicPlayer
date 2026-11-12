package org.example.music.favorite.exception;

public class FavoriteStoreUnavailableException extends RuntimeException {
    public FavoriteStoreUnavailableException(Throwable cause) {
        super("Favorite could not be stored", cause);
    }
}
