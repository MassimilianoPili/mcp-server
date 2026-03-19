package com.example.mcp.tools;

import io.github.massimilianopili.ai.reactive.annotation.ReactiveTool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class AnkiTools {

    private final WebClient webClient = WebClient.builder()
            .baseUrl("http://anki-api:8096")
            .defaultHeader("Accept", "application/json")
            .defaultHeader("Content-Type", "application/json")
            .build();

    // --- Deck operations ---

    @ReactiveTool(
            name = "anki_list_decks",
            description = "Lists all Anki decks with card counts (new, learning, due for review, total)."
    )
    public Mono<String> listDecks() {
        return webClient.get()
                .uri("/decks")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("{\"error\": \"" + e.getMessage() + "\"}"));
    }

    @ReactiveTool(
            name = "anki_create_deck",
            description = "Creates a new Anki deck. Supports hierarchy with :: (e.g.: 'Languages::Japanese::Kanji')."
    )
    public Mono<String> createDeck(
            @ToolParam(description = "Deck name, e.g.: 'Mathematics' or 'Languages::English::Vocabulary'") String name) {
        return webClient.post()
                .uri("/decks")
                .bodyValue("{\"name\": \"" + escapeJson(name) + "\"}")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("{\"error\": \"" + e.getMessage() + "\"}"));
    }

    // --- Note operations ---

    @ReactiveTool(
            name = "anki_add_note",
            description = "Adds a flashcard (note) to an Anki deck. Basic type with Front and Back."
    )
    public Mono<String> addNote(
            @ToolParam(description = "Target deck ID (obtainable from anki_list_decks)") long deckId,
            @ToolParam(description = "Front side text of the card (question). Supports HTML.") String front,
            @ToolParam(description = "Back side text of the card (answer). Supports HTML.") String back,
            @ToolParam(description = "Comma-separated tags, e.g.: 'vocabulary,level-1'. Empty for no tags.") String tags) {
        String tagsArray = tagsToJsonArray(tags);
        String body = String.format(
                "{\"deck_id\": %d, \"front\": \"%s\", \"back\": \"%s\", \"tags\": %s}",
                deckId, escapeJson(front), escapeJson(back), tagsArray);
        return webClient.post()
                .uri("/notes")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("{\"error\": \"" + e.getMessage() + "\"}"));
    }

    @ReactiveTool(
            name = "anki_bulk_add_notes",
            description = "Adds multiple flashcards in batch to a deck. More efficient than individual calls. " +
                          "The notes parameter is a JSON array of objects with front, back, tags."
    )
    public Mono<String> bulkAddNotes(
            @ToolParam(description = "Target deck ID") long deckId,
            @ToolParam(description = "JSON array of notes, e.g.: [{\"front\":\"Q1\",\"back\":\"A1\",\"tags\":[]},{\"front\":\"Q2\",\"back\":\"A2\",\"tags\":[\"tag1\"]}]") String notesJson) {
        String body = String.format("{\"deck_id\": %d, \"notes\": %s}", deckId, notesJson);
        return webClient.post()
                .uri("/bulk/notes")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("{\"error\": \"" + e.getMessage() + "\"}"));
    }

    @ReactiveTool(
            name = "anki_search_notes",
            description = "Searches Anki notes. Supports Anki search syntax (e.g.: 'tag:vocabulary', 'deck:Languages', free text)."
    )
    public Mono<String> searchNotes(
            @ToolParam(description = "Anki search query, e.g.: 'tag:important', 'front:*word*', free text. Empty for all.") String query,
            @ToolParam(description = "Deck ID to filter (0 for all decks)") long deckId) {
        return webClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/notes");
                    if (query != null && !query.isEmpty()) builder.queryParam("query", query);
                    if (deckId > 0) builder.queryParam("deck", deckId);
                    return builder.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("{\"error\": \"" + e.getMessage() + "\"}"));
    }

    @ReactiveTool(
            name = "anki_update_note",
            description = "Modifies the content of an existing note (front, back, tags). Only pass fields to update."
    )
    public Mono<String> updateNote(
            @ToolParam(description = "ID of the note to modify") long noteId,
            @ToolParam(description = "New front text (empty to leave unchanged)") String front,
            @ToolParam(description = "New back text (empty to leave unchanged)") String back,
            @ToolParam(description = "New comma-separated tags (empty to leave unchanged)") String tags) {
        StringBuilder body = new StringBuilder("{");
        boolean first = true;
        if (front != null && !front.isEmpty()) {
            body.append("\"front\": \"").append(escapeJson(front)).append("\"");
            first = false;
        }
        if (back != null && !back.isEmpty()) {
            if (!first) body.append(", ");
            body.append("\"back\": \"").append(escapeJson(back)).append("\"");
            first = false;
        }
        if (tags != null && !tags.isEmpty()) {
            if (!first) body.append(", ");
            body.append("\"tags\": ").append(tagsToJsonArray(tags));
        }
        body.append("}");

        return webClient.put()
                .uri("/notes/" + noteId)
                .bodyValue(body.toString())
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("{\"error\": \"" + e.getMessage() + "\"}"));
    }

    @ReactiveTool(
            name = "anki_delete_note",
            description = "Deletes a note and all its associated cards."
    )
    public Mono<String> deleteNote(
            @ToolParam(description = "ID of the note to delete") long noteId) {
        return webClient.delete()
                .uri("/notes/" + noteId)
                .retrieve()
                .bodyToMono(String.class)
                .defaultIfEmpty("{\"status\": \"deleted\"}")
                .onErrorResume(e -> Mono.just("{\"error\": \"" + e.getMessage() + "\"}"));
    }

    // --- Review / Scheduling ---

    @ReactiveTool(
            name = "anki_get_next_review",
            description = "Gets the next card due for review (spaced repetition). " +
                          "Returns front, back, current interval, ease factor, repetition count."
    )
    public Mono<String> getNextReview(
            @ToolParam(description = "Deck ID (0 for any deck)") long deckId) {
        return webClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/review/next");
                    if (deckId > 0) builder.queryParam("deck", deckId);
                    return builder.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("{\"error\": \"" + e.getMessage() + "\"}"));
    }

    @ReactiveTool(
            name = "anki_submit_review",
            description = "Submits a card review result. Rating: 1=Again, 2=Hard, 3=Good, 4=Easy. " +
                          "The FSRS algorithm automatically calculates the next interval."
    )
    public Mono<String> submitReview(
            @ToolParam(description = "ID of the card to rate") long cardId,
            @ToolParam(description = "Rating: 1=Again, 2=Hard, 3=Good, 4=Easy") int rating) {
        return webClient.post()
                .uri("/review/" + cardId)
                .bodyValue("{\"rating\": " + rating + "}")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("{\"error\": \"" + e.getMessage() + "\"}"));
    }

    @ReactiveTool(
            name = "anki_get_stats",
            description = "Deck statistics: total cards, due for review today, new, learning, mature, suspended, " +
                          "average ease, average interval."
    )
    public Mono<String> getStats(
            @ToolParam(description = "Deck ID (0 for global statistics)") long deckId) {
        return webClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/review/stats");
                    if (deckId > 0) builder.queryParam("deck", deckId);
                    return builder.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("{\"error\": \"" + e.getMessage() + "\"}"));
    }

    // --- Import/Export ---

    @ReactiveTool(
            name = "anki_export_deck",
            description = "Exports a deck as an .apkg file (native Anki format, importable on any client). " +
                          "Returns the path of the exported file."
    )
    public Mono<String> exportDeck(
            @ToolParam(description = "Deck ID to export (0 for entire collection)") long deckId) {
        return webClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/bulk/export");
                    if (deckId > 0) builder.queryParam("deck", deckId);
                    return builder.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("{\"error\": \"" + e.getMessage() + "\"}"));
    }

    // --- Helpers ---

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String tagsToJsonArray(String tags) {
        if (tags == null || tags.isEmpty()) return "[]";
        String[] parts = tags.split(",");
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(escapeJson(parts[i].trim())).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}
