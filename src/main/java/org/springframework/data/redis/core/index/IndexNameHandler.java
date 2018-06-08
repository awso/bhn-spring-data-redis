package org.springframework.data.redis.core.index;

public interface IndexNameHandler<T> {
    String getIndexName(T t);
}
