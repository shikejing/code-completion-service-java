package com.github.shikejing.codecompletionservicejava.model;

/**
 * Policy for edits whose baseVersion is ahead of the document version.
 * BUFFER: hold them and apply once the gap is filled (default).
 * REJECT: refuse them immediately.
 */
public enum OutOfOrderPolicy {
    BUFFER,
    REJECT
}
