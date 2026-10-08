package com.github.shikejing.codecompletionservicejava.web;

import com.github.shikejing.codecompletionservicejava.model.Edit;
import com.github.shikejing.codecompletionservicejava.model.Position;
import com.github.shikejing.codecompletionservicejava.model.TextRange;

/** JSON body for document synchronization. */
public record EditRequestDto(
        Long version,
        String editId,
        Edit.Kind kind,
        RangeDto range,
        String text,
        String fullText
) {
    public record RangeDto(PositionDto start, PositionDto end) {}
    public record PositionDto(int line, int character) {}

    public Edit toEdit() {
        TextRange r = range == null ? null
                : new TextRange(new Position(range.start().line(), range.start().character()),
                                new Position(range.end().line(), range.end().character()));
        return new Edit(version == null ? -1 : version, editId, kind, r, text, fullText);
    }
}
