package io.github.singhalgoru.watchroom;

import org.json.JSONException;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class CatalogTest {
    private static final String TV = """
            [{"show":{"name":"A Test Show","premiered":"2024-02-29","genres":["Drama"],
              "rating":{"average":8.5},"webChannel":{"name":"Example outlet","country":null},
              "url":"https://www.tvmaze.com/shows/1/example"}}]
            """;
    private static final String MOVIES = """
            {"results":[{"kind":"song","trackName":"Not a movie"},
              {"kind":"feature-movie","trackName":"A Test Movie","releaseDate":"2025-01-01T00:00:00Z",
               "primaryGenreName":"Drama","contentAdvisoryRating":"PG",
               "trackViewUrl":"https://itunes.apple.com/in/movie/test/id123"}]}
            """;

    @Test public void searchesAreEncodedAndIndiaScoped() throws Exception {
        assertEquals("https://api.tvmaze.com/search/shows?q=a+%26+b",
                Catalog.searchUrl(Catalog.Kind.TV, " a & b "));
        String url = Catalog.searchUrl(Catalog.Kind.MOVIES, "Hindi");
        assertTrue(url.contains("country=IN"));
        assertFalse(url.contains("entity="));
        assertFalse(url.contains("media="));
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankSearchIsRejected() throws Exception {
        Catalog.searchUrl(Catalog.Kind.TV, "  ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void longSearchIsRejected() throws Exception {
        Catalog.searchUrl(Catalog.Kind.TV, "a".repeat(81));
    }

    @Test public void tvMetadataKeepsSourceRatingAndOriginalOutlet() throws Exception {
        Catalog.Title title = Catalog.parse(Catalog.Kind.TV, TV).get(0);
        assertEquals("A Test Show", title.name);
        assertEquals("2024", title.year);
        assertEquals("8.5", title.rating);
        assertEquals("Example outlet", title.outlet);
        assertEquals("Drama", title.genre);
    }

    @Test public void movieSearchFiltersNonMoviesAndDoesNotInventScores() throws Exception {
        List<Catalog.Title> results = Catalog.parse(Catalog.Kind.MOVIES, MOVIES);
        assertEquals(1, results.size());
        assertEquals("A Test Movie", results.get(0).name);
        assertEquals("", results.get(0).rating);
        assertEquals("PG", results.get(0).age);
        assertEquals("", results.get(0).outlet);
    }

    @Test public void missingOptionalMetadataIsExplicitlyEmpty() throws Exception {
        String minimal = """
                [{"show":{"name":"Test","premiered":null,"rating":{"average":null},
                  "url":"https://www.tvmaze.com/shows/1"}}]
                """;
        Catalog.Title title = Catalog.parse(Catalog.Kind.TV, minimal).get(0);
        assertEquals("", title.rating);
        assertEquals("", title.year);
        assertEquals("", title.outlet);
        assertEquals("", title.summary);
    }

    @Test public void tvSynopsisComesFromProviderWithoutInventingCopy() throws Exception {
        String withSummary = TV.replace("\"genres\"", "\"summary\":\"<p>A provider synopsis.</p>\",\"genres\"");
        assertEquals("<p>A provider synopsis.</p>", Catalog.parse(Catalog.Kind.TV, withSummary).get(0).summary);
        assertEquals("", Catalog.parse(Catalog.Kind.MOVIES, MOVIES).get(0).summary);
    }

    @Test public void nullMovieMetadataDoesNotBecomeLiteralNull() throws Exception {
        String json = MOVIES.replace("\"Drama\"", "null").replace("\"PG\"", "null");
        Catalog.Title movie = Catalog.parse(Catalog.Kind.MOVIES, json).get(0);
        assertEquals("", movie.genre);
        assertEquals("", movie.age);
    }

    @Test(expected = JSONException.class)
    public void nullTitleIsNotAcceptedAsText() throws Exception {
        Catalog.parse(Catalog.Kind.TV, TV.replace("\"A Test Show\"", "null"));
    }

    @Test public void emptyCatalogsAreNotReplacedWithDemoResults() throws Exception {
        assertTrue(Catalog.parse(Catalog.Kind.TV, "[]").isEmpty());
        assertTrue(Catalog.parse(Catalog.Kind.MOVIES, "{\"results\":[]}").isEmpty());
    }

    @Test(expected = JSONException.class)
    public void malformedPayloadIsAnError() throws Exception {
        Catalog.parse(Catalog.Kind.MOVIES, "{\"error\":\"unavailable\"}");
    }

    @Test(expected = JSONException.class)
    public void invalidRatingIsAnError() throws Exception {
        Catalog.parse(Catalog.Kind.TV, TV.replace("8.5", "85"));
    }

    @Test(expected = JSONException.class)
    public void invalidDateIsAnError() throws Exception {
        Catalog.parse(Catalog.Kind.TV, TV.replace("2024-02-29", "2025-02-29"));
    }

    @Test(expected = JSONException.class)
    public void blankTitleIsAnError() throws Exception {
        Catalog.parse(Catalog.Kind.TV, TV.replace("A Test Show", " "));
    }

    @Test(expected = JSONException.class)
    public void unexpectedLinkHostIsRejected() throws Exception {
        Catalog.checkedLink("https://tvmaze.com.example.org/shows/1", Catalog.Kind.TV);
    }

    @Test(expected = JSONException.class)
    public void embeddedCredentialsAreRejected() throws Exception {
        Catalog.checkedLink("https://other@www.tvmaze.com/shows/1", Catalog.Kind.TV);
    }

    @Test(expected = JSONException.class)
    public void cleartextLinksAreRejected() throws Exception {
        Catalog.checkedLink("http://itunes.apple.com/in/movie/test/id1", Catalog.Kind.MOVIES);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void catalogResultsAreImmutable() throws Exception {
        Catalog.parse(Catalog.Kind.TV, TV).clear();
    }

    @Test public void missingArtworkRemainsExplicitlyEmpty() throws Exception {
        assertEquals("", Catalog.parse(Catalog.Kind.TV, TV).get(0).artwork);
    }

    @Test public void onlyProviderMediumPortraitsAreUsed() throws Exception {
        String address = "https://static.tvmaze.com/uploads/images/medium_portrait/1/1.jpg";
        String json = TV.replace("\"genres\":", "\"image\":{\"medium\":\"" + address + "\"},\"genres\":");
        assertEquals(address, Catalog.parse(Catalog.Kind.TV, json).get(0).artwork);
    }

    @Test(expected = JSONException.class)
    public void arbitraryArtworkHostsAreRejected() throws Exception {
        String json = TV.replace("\"genres\":",
                "\"image\":{\"medium\":\"https://example.org/private\"},\"genres\":");
        Catalog.parse(Catalog.Kind.TV, json);
    }
}
