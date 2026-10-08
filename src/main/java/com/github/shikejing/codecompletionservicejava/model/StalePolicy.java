package com.github.shikejing.codecompletionservicejava.model;

/**
 * Policy for edits whose baseVersion is older than the document version.
 * REJECT: refuse the edit (default, strict LSP semantics).
 * REBASE: translate the edit range against intervening edits and apply.
 */
public enum StalePolicy {
    REJECT,
    REBASE
}
