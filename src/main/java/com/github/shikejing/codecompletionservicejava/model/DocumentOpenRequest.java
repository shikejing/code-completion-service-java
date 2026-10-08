package com.github.shikejing.codecompletionservicejava.model;

public record DocumentOpenRequest(String uri, String text, int version) {
}
