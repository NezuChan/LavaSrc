
package com.github.topi314.lavasrc.spotify;

import com.github.topi314.lavalyrics.AudioLyricsManager;
import com.github.topi314.lavalyrics.lyrics.AudioLyrics;
import com.github.topi314.lavalyrics.lyrics.BasicAudioLyrics;
import com.github.topi314.lavasearch.AudioSearchManager;
import com.github.topi314.lavasearch.result.AudioSearchResult;
import com.github.topi314.lavasearch.result.BasicAudioSearchResult;
import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.github.topi314.lavasrc.LavaSrcTools;
import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.github.topi314.lavasrc.mirror.MirroringAudioSourceManager;
import com.github.topi314.lavasrc.mirror.MirroringAudioTrackResolver;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SpotifySourceManager extends MirroringAudioSourceManager implements HttpConfigurable, AudioSearchManager, AudioLyricsManager {

	public static final Pattern URL_PATTERN = Pattern.compile("(https?://)(www\\.)?open\\.spotify\\.com/((?<region>[a-zA-Z-]+)/)?(user/(?<user>[a-zA-Z0-9-_]+)/)?(?<type>track|album|playlist|artist)/(?<identifier>[a-zA-Z0-9-_]+)");
	public static final Pattern RADIO_MIX_QUERY_PATTERN = Pattern.compile("mix:(?<seedType>album|artist|track|isrc):(?<seed>[a-zA-Z0-9-_]+)");
	public static final String SEARCH_PREFIX = "spsearch:";
	public static final String RECOMMENDATIONS_PREFIX = "sprec:";
	public static final String PREVIEW_PREFIX = "spprev:";
	public static final long PREVIEW_LENGTH = 30000;
	public static final String SHARE_URL = "https://spotify.link/";
	public static final int PLAYLIST_MAX_PAGE_ITEMS = 100;
	public static final int ALBUM_MAX_PAGE_ITEMS = 50;
	public static final String API_BASE = "https://api.spotify.com/v1/";
	public static final String CLIENT_API_BASE = "https://spclient.wg.spotify.com/";
	public static final String INTERNAL_API_BASE = "https://api-partner.spotify.com/pathfinder/v2/query";
	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.178 Spotify/1.2.65.255 Safari/537.36";
	public static final Set<AudioSearchResult.Type> SEARCH_TYPES = Set.of(AudioSearchResult.Type.ALBUM, AudioSearchResult.Type.ARTIST, AudioSearchResult.Type.PLAYLIST, AudioSearchResult.Type.TRACK);
	// Persisted GraphQL query definitions: {operationName, sha256Hash}
	private static final String[] QUERY_GET_TRACK = {"getTrack", "612585ae06ba435ad26369870deaae23b5c8800a256cd8a57e08eddc25a37294"};
	private static final String[] QUERY_GET_ALBUM = {"getAlbum", "b9bfabef66ed756e5e13f68a942deb60bd4125ec1f1be8cc42769dc0259b4b10"};
	private static final String[] QUERY_GET_PLAYLIST = {"fetchPlaylist", "bb67e0af06e8d6f52b531f97468ee4acd44cd0f82b988e15c2ea47b1148efc77"};
	private static final String[] QUERY_GET_ARTIST = {"queryArtistOverview", "35648a112beb1794e39ab931365f6ae4a8d45e65396d641eeda94e4003d41497"};
	private static final String[] QUERY_GET_RECOMMENDATIONS = {"internalLinkRecommenderTrack", "c77098ee9d6ee8ad3eb844938722db60570d040b49f41f5ec6e7be9160a7c86b"};
	private static final String[] QUERY_SEARCH_DESKTOP = {"searchDesktop", "fcad5a3e0d5af727fb76966f06971c19cfa2275e6ff7671196753e008611873c"};
	private static final Logger log = LoggerFactory.getLogger(SpotifySourceManager.class);

	private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
	private final SpotifyTokenTracker tokenTracker;
	private final String countryCode;
	private int playlistPageLimit = 6;
	private int albumPageLimit = 6;
	private boolean localFiles;
	private boolean resolveArtistsInSearch = true;
	private boolean preferAnonymousToken = false;
	

	public SpotifySourceManager(String[] providers, String clientId, String clientSecret, String countryCode, AudioPlayerManager audioPlayerManager) {
		this(clientId, clientSecret, null, countryCode, unused -> audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
	}

	public SpotifySourceManager(String[] providers, String clientId, String clientSecret, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager) {
		this(clientId, clientSecret, null, countryCode, audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
	}

	public SpotifySourceManager(String clientId, String clientSecret, String countryCode, AudioPlayerManager audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		this(clientId, clientSecret, null, countryCode, unused -> audioPlayerManager, mirroringAudioTrackResolver);
	}

	public SpotifySourceManager(String clientId, String clientSecret, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		this(clientId, clientSecret, null, countryCode, audioPlayerManager, mirroringAudioTrackResolver);
	}

	public SpotifySourceManager(String clientId, String clientSecret, String spDc, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		this(clientId, clientSecret, false , spDc, countryCode, audioPlayerManager, mirroringAudioTrackResolver);
	}

	public SpotifySourceManager(String clientId, String clientSecret, boolean preferAnonymousToken, String spDc, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		this(clientId, clientSecret, preferAnonymousToken, null, spDc, countryCode, audioPlayerManager, mirroringAudioTrackResolver);
	}

	public SpotifySourceManager(String clientId, String clientSecret, boolean preferAnonymousToken, String customTokenEndpoint, String spDc, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		super(audioPlayerManager, mirroringAudioTrackResolver);

		this.tokenTracker = new SpotifyTokenTracker(this, clientId, clientSecret, spDc, customTokenEndpoint);

		if (countryCode == null || countryCode.isEmpty()) {
			countryCode = "US";
		}
		this.countryCode = countryCode;
		this.preferAnonymousToken = preferAnonymousToken;
	}

	public void setPlaylistPageLimit(int playlistPageLimit) {
		this.playlistPageLimit = playlistPageLimit;
	}

	public void setAlbumPageLimit(int albumPageLimit) {
		this.albumPageLimit = albumPageLimit;
	}

	public void setLocalFiles(boolean localFiles) {
		this.localFiles = localFiles;
	}

	public void setResolveArtistsInSearch(boolean resolveArtistsInSearch) {
		this.resolveArtistsInSearch = resolveArtistsInSearch;
	}

	public void setClientIDSecret(String clientId, String clientSecret) {
		this.tokenTracker.setClientIDS(clientId, clientSecret);
	}

	public void setSpDc(String spDc) {
		this.tokenTracker.setSpDc(spDc);
	}

	public void setPreferAnonymousToken(boolean preferAnonymousToken) {
		this.preferAnonymousToken = preferAnonymousToken;
	}

	public void setCustomTokenEndpoint(String customTokenEndpoint) {
		this.tokenTracker.setCustomTokenEndpoint(customTokenEndpoint);
	}

	@NotNull
	@Override
	public String getSourceName() {
		return "spotify";
	}

	@Override
	@Nullable
	public AudioLyrics loadLyrics(@NotNull AudioTrack audioTrack) {
		var spotifyTackId = "";
		if (audioTrack instanceof SpotifyAudioTrack) {
			spotifyTackId = audioTrack.getIdentifier();
		}

		if (spotifyTackId.isEmpty()) {
			AudioItem item = AudioReference.NO_TRACK;
			try {
				if (audioTrack.getInfo().isrc != null && !audioTrack.getInfo().isrc.isEmpty()) {
					item = this.getSearch("isrc:" + audioTrack.getInfo().isrc, false);
				}
				if (item == AudioReference.NO_TRACK) {
					item = this.getSearch(String.format("%s %s", audioTrack.getInfo().title, audioTrack.getInfo().author), false);
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}

			if (item == AudioReference.NO_TRACK) {
				return null;
			}
			if (item instanceof AudioTrack) {
				spotifyTackId = ((AudioTrack) item).getIdentifier();
			} else if (item instanceof AudioPlaylist) {
				var playlist = (AudioPlaylist) item;
				if (!playlist.getTracks().isEmpty()) {
					spotifyTackId = playlist.getTracks().get(0).getIdentifier();
				}
			}
		}

		try {
			return this.getLyrics(spotifyTackId);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public AudioLyrics getLyrics(String id) throws IOException {
		if (!this.tokenTracker.hasValidAccountCredentials()) {
			throw new IllegalArgumentException("Spotify spDc must be set");
		}

		var request = new HttpGet(CLIENT_API_BASE + "color-lyrics/v2/track/" + id + "?format=json&vocalRemoval=false");
		request.setHeader("User-Agent", USER_AGENT);
		request.setHeader("App-Platform", "WebPlayer");
		request.setHeader("Authorization", "Bearer " + this.tokenTracker.getAccountAccessToken());
		var json = LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
		if (json == null) {
			return null;
		}

		var lyrics = new ArrayList<AudioLyrics.Line>();
		for (var line : json.get("lyrics").get("lines").values()) {
			lyrics.add(new BasicAudioLyrics.BasicLine(
				Duration.ofMillis(line.get("startTimeMs").asLong(0)),
				null,
				line.get("words").text()
			));
		}

		return new BasicAudioLyrics("spotify", json.get("lyrics").get("providerDisplayName").textOrDefault("MusixMatch"), null, lyrics);
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		var extendedAudioTrackInfo = super.decodeTrack(input);
		return new SpotifyAudioTrack(trackInfo,
			extendedAudioTrackInfo.albumName,
			extendedAudioTrackInfo.albumUrl,
			extendedAudioTrackInfo.artistUrl,
			extendedAudioTrackInfo.artistArtworkUrl,
			extendedAudioTrackInfo.previewUrl,
			extendedAudioTrackInfo.isPreview,
			this
		);
	}

	@Override
	@Nullable
	public AudioSearchResult loadSearch(@NotNull String query, @NotNull Set<AudioSearchResult.Type> types) {
		try {
			if (query.startsWith(SEARCH_PREFIX)) {
				return this.getAutocomplete(query.substring(SEARCH_PREFIX.length()), types);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		var identifier = reference.identifier;
		var preview = reference.identifier.startsWith(PREVIEW_PREFIX);
		return this.loadItem(preview ? identifier.substring(PREVIEW_PREFIX.length()) : identifier, preview);
	}

	public AudioItem loadItem(String identifier, boolean preview) {
		try {
			if (identifier.startsWith(SEARCH_PREFIX)) {
				return this.getSearch(identifier.substring(SEARCH_PREFIX.length()).trim(), preview);
			}

			if (identifier.startsWith(RECOMMENDATIONS_PREFIX)) {
				return this.getRecommendations(identifier.substring(RECOMMENDATIONS_PREFIX.length()).trim(), preview);
			}

			// If the identifier is a share URL, we need to follow the redirect to find out the real url behind it
			if (identifier.startsWith(SHARE_URL)) {
				var request = new HttpHead(identifier);
				request.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
				try (var response = this.httpInterfaceManager.getInterface().execute(request)) {
					if (response.getStatusLine().getStatusCode() == 307) {
						var location = response.getFirstHeader("Location").getValue();
						if (location.startsWith("https://open.spotify.com/")) {
							return this.loadItem(location, preview);
						}
					}
					return null;
				}
			}

			var matcher = URL_PATTERN.matcher(identifier);
			if (!matcher.find()) {
				return null;
			}

			var id = matcher.group("identifier");
			switch (matcher.group("type")) {
				case "album":
					return this.getAlbum(id, preview);

				case "track":
					return this.getTrack(id, preview);

				case "playlist":
					return this.getPlaylist(id, preview);

				case "artist":
					return this.getArtist(id, preview);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	public JsonBrowser getJson(String uri, boolean anonymous, boolean preferAnonymous) throws IOException {
		var request = new HttpGet(uri);
		var accessToken = anonymous ? this.tokenTracker.getAnonymousAccessToken() : this.tokenTracker.getAccessToken(preferAnonymous);
		request.addHeader("Authorization", "Bearer " + accessToken);
		return LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
	}

	/**
	 * POST to the Spotify internal GraphQL API using a persisted query.
	 * Uses the anonymous token when available, falling back to the official access token.
	 *
	 * @param query      two-element array: [operationName, sha256Hash]
	 * @param variables  raw JSON string of the variables object
	 * @return the {@code data} node of the response, or {@code null} on error
	 */
	public JsonBrowser postInternalApiJson(String[] query, String variables) throws IOException {
		String token;
		try {
			token = this.tokenTracker.getAnonymousAccessToken();
		} catch (Exception e) {
			log.debug("Could not obtain anonymous token for internal API, trying official token", e);
			try {
				token = this.tokenTracker.getAccessToken(false);
			} catch (Exception ex) {
				return null;
			}
		}
		if (token == null) return null;

		var body = "{\"variables\":" + variables
			+ ",\"operationName\":\"" + query[0] + "\""
			+ ",\"extensions\":{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"" + query[1] + "\"}}}";

		var request = new HttpPost(INTERNAL_API_BASE);
		request.addHeader("Authorization", "Bearer " + token);
		request.addHeader("App-Platform", "WebPlayer");
		request.addHeader("Spotify-App-Version", "1.2.81.104.g225ec0e6");
		request.addHeader("Content-Type", "application/json; charset=utf-8");
		request.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

		var json = LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
		if (json == null || !json.get("errors").isNull()) {
			if (json != null) {
				log.debug("Internal API error for operation {}: {}", query[0], json.get("errors"));
			}
			return null;
		}
		return json.get("data");
	}

	/**
	 * Build a {@link SpotifyAudioTrack} from an internal GraphQL track node
	 * (e.g. {@code trackUnion}, {@code itemV2.data}, {@code item.track}).
	 */
	private SpotifyAudioTrack parseInternalTrack(JsonBrowser json) {
		return parseInternalTrack(json, null);
	}

	private SpotifyAudioTrack parseInternalTrack(JsonBrowser json, String overrideArtworkUrl) {
		if (json == null || json.isNull()) return null;

		// Resolve Spotify track ID from URI or direct id field
		var uriText = json.get("uri").text();
		String id;
		if (uriText != null && uriText.startsWith("spotify:track:")) {
			id = uriText.split(":")[2];
		} else {
			id = json.get("id").text();
		}
		if (id == null) return null;

		var name = json.get("name").safeText();
		if (name.isEmpty()) return null;

		// artists: internal format uses artists.items[].profile.name
		var artistSb = new StringBuilder();
		for (var artist : json.get("artists").get("items").values()) {
			if (artistSb.length() > 0) artistSb.append(", ");
			var n = artist.get("profile").get("name").text();
			if (n != null) artistSb.append(n);
		}
		// fallback: firstArtist (used in some playlist item shapes)
		if (artistSb.length() == 0) {
			var n = json.get("firstArtist").get("items").index(0).get("profile").get("name").text();
			if (n != null) artistSb.append(n);
		}
		if (artistSb.length() == 0) artistSb.append("Unknown");

		// duration: internal uses duration.totalMilliseconds or trackDuration.totalMilliseconds
		var duration = json.get("duration").get("totalMilliseconds").asLong(0);
		if (duration == 0) duration = json.get("trackDuration").get("totalMilliseconds").asLong(0);

		// artwork
		var artwork = overrideArtworkUrl;
		if (artwork == null) {
			artwork = json.get("albumOfTrack").get("coverArt").get("sources").index(0).get("url").text();
		}
		if (artwork == null) {
			artwork = json.get("album").get("images").index(0).get("url").text();
		}

		// album metadata
		var albumName = json.get("albumOfTrack").get("name").text();
		var albumUrl = json.get("albumOfTrack").get("sharingInfo").get("shareUrl").text();

		// artist URL
		String artistUrl = null;
		var artistUriNode = json.get("artists").get("items").index(0).get("uri").text();
		if (artistUriNode != null) {
			var parts = artistUriNode.split(":");
			if (parts.length == 3) artistUrl = "https://open.spotify.com/artist/" + parts[2];
		}

		// ISRC: internal uses externalIds.isrc
		var isrc = json.get("externalIds").get("isrc").text();
		if (isrc == null) isrc = json.get("external_ids").get("isrc").text();

		return new SpotifyAudioTrack(
			new AudioTrackInfo(
				name,
				artistSb.toString(),
				duration,
				id,
				false,
				"https://open.spotify.com/track/" + id,
				artwork,
				isrc
			),
			albumName,
			albumUrl,
			artistUrl,
			null,
			null,
			false,
			this
		);
	}

	private AudioSearchResult getAutocomplete(String query, Set<AudioSearchResult.Type> types) throws IOException {
		if (types.isEmpty()) {
			types = SEARCH_TYPES;
		}
		var url = API_BASE + "search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&type=" + types.stream().map(AudioSearchResult.Type::getName).collect(Collectors.joining(","));
		var json = this.getJson(url, false, false);
		if (json == null) {
			return AudioSearchResult.EMPTY;
		}

		var albums = new ArrayList<AudioPlaylist>();
		for (var album : json.get("albums").get("items").values()) {
			albums.add(new SpotifyAudioPlaylist(
				album.get("name").safeText(),
				Collections.emptyList(),
				ExtendedAudioPlaylist.Type.ALBUM,
				album.get("external_urls").get("spotify").text(),
				album.get("images").index(0).get("url").text(),
				album.get("artists").index(0).get("name").text(),
				(int) album.get("total_tracks").asLong(0)
			));
		}

		var artists = new ArrayList<AudioPlaylist>();
		for (var artist : json.get("artists").get("items").values()) {
			artists.add(new SpotifyAudioPlaylist(
				artist.get("name").safeText() + "'s Top Tracks",
				Collections.emptyList(),
				ExtendedAudioPlaylist.Type.ARTIST,
				artist.get("external_urls").get("spotify").text(),
				artist.get("images").index(0).get("url").text(),
				artist.get("name").text(),
				null
			));
		}

		var playlists = new ArrayList<AudioPlaylist>();
		for (var playlist : json.get("playlists").get("items").values()) {
			playlists.add(new SpotifyAudioPlaylist(
				playlist.get("name").safeText(),
				Collections.emptyList(),
				ExtendedAudioPlaylist.Type.PLAYLIST,
				playlist.get("external_urls").get("spotify").text(),
				playlist.get("images").index(0).get("url").text(),
				playlist.get("owner").get("display_name").text(),
				(int) playlist.get("tracks").get("total").asLong(0)
			));
		}

		var tracks = this.parseTrackItems(json.get("tracks"), false);

		return new BasicAudioSearchResult(tracks, albums, artists, playlists, new ArrayList<>());
	}

	public AudioItem getSearch(String query, boolean preview) throws IOException {
		// Try internal searchDesktop first (works with anonymous token)
		try {
			var vars = "{\"searchTerm\":" + jsonString(query)
				+ ",\"offset\":0,\"limit\":10,\"numberOfTopResults\":5"
				+ ",\"includeAudiobooks\":false,\"includeArtistHasConcertsField\":false,\"includePreReleases\":false}";
			var data = this.postInternalApiJson(QUERY_SEARCH_DESKTOP, vars);
			if (data != null && !data.get("searchV2").isNull()) {
				var tracks = new ArrayList<AudioTrack>();
				for (var item : data.get("searchV2").get("tracksV2").get("items").values()) {
					var track = this.parseInternalTrack(item.get("item").get("data"));
					if (track != null) tracks.add(track);
				}
				if (!tracks.isEmpty()) {
					return new BasicAudioPlaylist("Spotify Search: " + query, tracks, null, true);
				}
			}
		} catch (Exception e) {
			log.debug("Internal searchDesktop failed, falling back to official API", e);
		}

		// Fallback: official API
		var json = this.getJson(API_BASE + "search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&type=track", false, false);
		if (json == null || json.get("tracks").get("items").values().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		if (this.resolveArtistsInSearch) {
			var artistIds = json.get("tracks").get("items").values().stream().map(track -> track.get("artists").index(0).get("id").text()).collect(Collectors.joining(","));
			var artistJson = this.getJson(API_BASE + "artists?ids=" + artistIds, false, false);
			if (artistJson != null) {
				for (var artist : artistJson.get("artists").values()) {
					for (var track : json.get("tracks").get("items").values()) {
						if (track.get("artists").index(0).get("id").text().equals(artist.get("id").text())) {
							track.get("artists").index(0).put("images", artist.get("images"));
						}
					}
				}
			}
		}

		return new BasicAudioPlaylist("Spotify Search: " + query, this.parseTrackItems(json.get("tracks"), preview), null, true);
	}

	public AudioItem getRecommendations(String query, boolean preview) throws IOException {
		Matcher matcher = RADIO_MIX_QUERY_PATTERN.matcher(query);
		if (matcher.find()) {
			String seedType = matcher.group("seedType");
			String seed = matcher.group("seed");
			if (seedType.equals("isrc")) {
				AudioItem item = this.getSearch("isrc:" + seed, preview);
				if (item == AudioReference.NO_TRACK) {
					return AudioReference.NO_TRACK;
				}
				if (item instanceof AudioTrack) {
					seed = ((AudioTrack) item).getIdentifier();
					seedType = "track";
				} else if (item instanceof AudioPlaylist) {
					var playlist = (AudioPlaylist) item;
					if (!playlist.getTracks().isEmpty()) {
						seed = playlist.getTracks().get(0).getIdentifier();
						seedType = "track";
					} else {
						return AudioReference.NO_TRACK;
					}
				}
			}

			// Try the internal recommendations endpoint first
			if (seedType.equals("track")) {
				try {
					var vars = "{\"uri\":\"spotify:track:" + seed + "\",\"limit\":20}";
					var data = this.postInternalApiJson(QUERY_GET_RECOMMENDATIONS, vars);
					if (data != null) {
						var items = data.get("internalLinkRecommenderTrack").get("items");
						if (items.isNull() || items.values().isEmpty()) {
							items = data.get("seoRecommendedTrack").get("items");
						}
						if (!items.isNull() && !items.values().isEmpty()) {
							var tracks = new ArrayList<AudioTrack>();
							for (var item : items.values()) {
								var contentData = item.get("content").get("data");
								if (contentData.isNull()) contentData = item.get("data");
								var track = this.parseInternalTrack(contentData);
								if (track != null) tracks.add(track);
							}
							if (!tracks.isEmpty()) {
								return new SpotifyAudioPlaylist("Spotify Recommendations:", tracks, ExtendedAudioPlaylist.Type.RECOMMENDATIONS, null, null, null, null);
							}
						}
					}
				} catch (Exception e) {
					log.debug("Internal recommendations failed, trying inspiredby-mix", e);
				}
			}

			JsonBrowser rjson = this.getJson(CLIENT_API_BASE + "inspiredby-mix/v2/seed_to_playlist/spotify:" + seedType + ":" + seed + "?response-format=json", true, this.preferAnonymousToken);
			JsonBrowser mediaItems = rjson.get("mediaItems");
			if (mediaItems.isList() && mediaItems.values().size() > 0) {
				String playlistId = mediaItems.index(0).get("uri").text().split(":")[2];
				return this.getPlaylist(playlistId, preview);
			}
		}

		var json = this.getJson(API_BASE + "recommendations?" + query, false, false);
		if (json == null || json.get("tracks").values().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new SpotifyAudioPlaylist("Spotify Recommendations:", this.parseTracks(json, preview), ExtendedAudioPlaylist.Type.RECOMMENDATIONS, null, null, null, null);
	}

	public AudioItem getAlbum(String id, boolean preview) throws IOException {
		// Try internal API first
		try {
			var vars = "{\"uri\":\"spotify:album:" + id + "\",\"locale\":\"en\",\"offset\":0,\"limit\":300}";
			var data = this.postInternalApiJson(QUERY_GET_ALBUM, vars);
			if (data != null && !data.get("albumUnion").isNull()
					&& !"NotFound".equals(data.get("albumUnion").get("__typename").text())) {
				var albumUnion = data.get("albumUnion");
				var artworkUrl = albumUnion.get("coverArt").get("sources").index(0).get("url").text();
				var tracks = new ArrayList<AudioTrack>();
				var trackItems = albumUnion.get("tracksV2").get("items").values();
				int totalTracks = (int) albumUnion.get("tracksV2").get("totalCount").asLong(trackItems.size());
				for (var item : trackItems) {
					var track = this.parseInternalTrack(item.get("track"), artworkUrl);
					if (track != null) tracks.add(track);
				}
				// fetch additional pages if needed
				int offset = 300;
				int pages = 0;
				while (offset < totalTracks && (this.albumPageLimit <= 0 || pages < this.albumPageLimit)) {
					var pageVars = "{\"uri\":\"spotify:album:" + id + "\",\"locale\":\"en\",\"offset\":" + offset + ",\"limit\":300}";
					var pageData = this.postInternalApiJson(QUERY_GET_ALBUM, pageVars);
					if (pageData == null) break;
					for (var item : pageData.get("albumUnion").get("tracksV2").get("items").values()) {
						var track = this.parseInternalTrack(item.get("track"), artworkUrl);
						if (track != null) tracks.add(track);
					}
					offset += 300;
					pages++;
				}
				if (!tracks.isEmpty()) {
					var albumName = albumUnion.get("name").safeText();
					var albumUrl = albumUnion.get("sharingInfo").get("shareUrl").text();
					var artistName = albumUnion.get("artists").get("items").index(0).get("profile").get("name").text();
					return new SpotifyAudioPlaylist(albumName, tracks, ExtendedAudioPlaylist.Type.ALBUM, albumUrl, artworkUrl, artistName, totalTracks);
				}
			}
		} catch (Exception e) {
			log.debug("Internal getAlbum failed, falling back to official API", e);
		}

		// Fallback: official API
		var json = this.getJson(API_BASE + "albums/" + id, false, this.preferAnonymousToken);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var artistJson = this.getJson(API_BASE + "artists/" + json.get("artists").index(0).get("id").text(), false, this.preferAnonymousToken);
		if (artistJson == null) {
			artistJson = JsonBrowser.newMap();
		}

		var tracks = new ArrayList<AudioTrack>();
		JsonBrowser page;
		var offset = 0;
		var pages = 0;
		do {
			page = this.getJson(API_BASE + "albums/" + id + "/tracks?limit=" + ALBUM_MAX_PAGE_ITEMS + "&offset=" + offset, false, this.preferAnonymousToken);
			offset += ALBUM_MAX_PAGE_ITEMS;

			var tracksPage = this.getJson(API_BASE + "tracks/?ids=" + page.get("items").values().stream().map(track -> track.get("id").text()).collect(Collectors.joining(",")), false, this.preferAnonymousToken);

			for (var track : tracksPage.get("tracks").values()) {
				var albumJson = JsonBrowser.newMap();
				albumJson.put("external_urls", json.get("external_urls"));
				albumJson.put("name", json.get("name"));
				albumJson.put("images", json.get("images"));
				track.put("album", albumJson);

				track.get("artists").index(0).put("images", artistJson.get("images"));
			}

			tracks.addAll(this.parseTracks(tracksPage, preview));
		}
		while (page.get("next").text() != null && ++pages < this.albumPageLimit);

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new SpotifyAudioPlaylist(json.get("name").safeText(), tracks, ExtendedAudioPlaylist.Type.ALBUM, json.get("external_urls").get("spotify").text(), json.get("images").index(0).get("url").text(), json.get("artists").index(0).get("name").text(), (int) json.get("total_tracks").asLong(0));
	}

	public AudioItem getPlaylist(String id, boolean preview) throws IOException {
		// Autogenerated playlists (e.g. Discover Weekly) require the internal API
		var isAutogenerated = id.startsWith("37i9dQZF") || id.startsWith("37i9dQZE");

		// Try internal API first
		try {
			var vars = "{\"uri\":\"spotify:playlist:" + id + "\",\"offset\":0,\"limit\":100,\"enableWatchFeedEntrypoint\":false}";
			var data = this.postInternalApiJson(QUERY_GET_PLAYLIST, vars);
			if (data != null && !data.get("playlistV2").isNull()
					&& !"NotFound".equals(data.get("playlistV2").get("__typename").text())) {
				var playlistV2 = data.get("playlistV2");
				var tracks = new ArrayList<AudioTrack>();
				var contentItems = playlistV2.get("content").get("items").values();
				int totalTracks = (int) playlistV2.get("content").get("totalCount").asLong(contentItems.size());
				for (var item : contentItems) {
					var track = this.parseInternalTrack(item.get("itemV2").get("data"));
					if (track != null) tracks.add(track);
				}
				// fetch additional pages
				int offset = 100;
				int pages = 0;
				while (offset < totalTracks && (this.playlistPageLimit <= 0 || pages < this.playlistPageLimit)) {
					var pageVars = "{\"uri\":\"spotify:playlist:" + id + "\",\"offset\":" + offset + ",\"limit\":100,\"enableWatchFeedEntrypoint\":false}";
					var pageData = this.postInternalApiJson(QUERY_GET_PLAYLIST, pageVars);
					if (pageData == null) break;
					for (var item : pageData.get("playlistV2").get("content").get("items").values()) {
						var track = this.parseInternalTrack(item.get("itemV2").get("data"));
						if (track != null) tracks.add(track);
					}
					offset += 100;
					pages++;
				}
				if (!tracks.isEmpty() || isAutogenerated) {
					var playlistName = playlistV2.get("name").safeText();
					var artworkUrl = playlistV2.get("images").get("items").index(0).get("sources").index(0).get("url").text();
					var ownerName = playlistV2.get("ownerV2").get("data").get("name").text();
					return new SpotifyAudioPlaylist(playlistName, tracks, ExtendedAudioPlaylist.Type.PLAYLIST, "https://open.spotify.com/playlist/" + id, artworkUrl, ownerName, totalTracks);
				}
			}
		} catch (Exception e) {
			log.debug("Internal getPlaylist failed, falling back to official API", e);
		}

		if (isAutogenerated) {
			log.warn("Autogenerated playlist {} could not be loaded via internal API.", id);
			return AudioReference.NO_TRACK;
		}

		// Fallback: official API
		var anonymous = id.startsWith("37i9dQZ");
		var json = this.getJson(API_BASE + "playlists/" + id, anonymous, this.preferAnonymousToken);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var tracks = new ArrayList<AudioTrack>();
		JsonBrowser page;
		var offset = 0;
		var pages = 0;
		do {
			page = this.getJson(API_BASE + "playlists/" + id + "/tracks?limit=" + PLAYLIST_MAX_PAGE_ITEMS + "&offset=" + offset, anonymous, this.preferAnonymousToken);
			offset += PLAYLIST_MAX_PAGE_ITEMS;

			for (var value : page.get("items").values()) {
				var track = value.get("track");
				if (track.isNull() || track.get("type").text().equals("episode") || (!this.localFiles && track.get("is_local").asBoolean(false))) {
					continue;
				}

				tracks.add(this.parseTrack(track, preview));
			}

		}
		while (page.get("next").text() != null && ++pages < this.playlistPageLimit);

		return new SpotifyAudioPlaylist(json.get("name").safeText(), tracks, ExtendedAudioPlaylist.Type.PLAYLIST, json.get("external_urls").get("spotify").text(), json.get("images").index(0).get("url").text(), json.get("owner").get("display_name").text(), (int) json.get("tracks").get("total").asLong(0));
	}

	public AudioItem getArtist(String id, boolean preview) throws IOException {
		// Try internal API first
		try {
			var vars = "{\"uri\":\"spotify:artist:" + id + "\",\"locale\":\"en\",\"includePrerelease\":true}";
			var data = this.postInternalApiJson(QUERY_GET_ARTIST, vars);
			if (data != null && !data.get("artistUnion").isNull()
					&& !"NotFound".equals(data.get("artistUnion").get("__typename").text())) {
				var artistUnion = data.get("artistUnion");
				var artistName = artistUnion.get("profile").get("name").safeText();
				var artworkUrl = artistUnion.get("visuals").get("avatarImage").get("sources").index(0).get("url").text();
				var tracks = new ArrayList<AudioTrack>();
				for (var item : artistUnion.get("discography").get("topTracks").get("items").values()) {
					var track = this.parseInternalTrack(item.get("track"), artworkUrl);
					if (track != null) tracks.add(track);
				}
				if (!tracks.isEmpty()) {
					return new SpotifyAudioPlaylist(artistName + "'s Top Tracks", tracks, ExtendedAudioPlaylist.Type.ARTIST, "https://open.spotify.com/artist/" + id, artworkUrl, artistName, tracks.size());
				}
			}
		} catch (Exception e) {
			log.debug("Internal getArtist failed, falling back to official API", e);
		}

		// Fallback: official API
		var json = this.getJson(API_BASE + "artists/" + id, false, this.preferAnonymousToken);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var tracksJson = this.getJson(API_BASE + "artists/" + id + "/top-tracks?market=" + this.countryCode, false, this.preferAnonymousToken);
		if (tracksJson == null || tracksJson.get("tracks").values().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		for (var track : tracksJson.get("tracks").values()) {
			track.get("artists").index(0).put("images", json.get("images"));
		}

		return new SpotifyAudioPlaylist(json.get("name").safeText() + "'s Top Tracks", this.parseTracks(tracksJson, preview), ExtendedAudioPlaylist.Type.ARTIST, json.get("external_urls").get("spotify").text(), json.get("images").index(0).get("url").text(), json.get("name").text(), (int) tracksJson.get("tracks").get("total").asLong(0));
	}

	public AudioItem getTrack(String id, boolean preview) throws IOException {
		// Try internal API first (uses anonymous token, no client credentials required)
		if (!preview) {
			try {
				var data = this.postInternalApiJson(QUERY_GET_TRACK, "{\"uri\":\"spotify:track:" + id + "\"}");
				if (data != null && !data.get("trackUnion").isNull()
						&& !"NotFound".equals(data.get("trackUnion").get("__typename").text())) {
					var track = this.parseInternalTrack(data.get("trackUnion"));
					if (track != null) return track;
				}
			} catch (Exception e) {
				log.debug("Internal getTrack failed, falling back to official API", e);
			}
		}

		// Fallback: official API
		var json = this.getJson(API_BASE + "tracks/" + id, false, this.preferAnonymousToken);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var artistJson = this.getJson(API_BASE + "artists/" + json.get("artists").index(0).get("id").text(), false, this.preferAnonymousToken);
		if (artistJson != null) {
			json.get("artists").index(0).put("images", artistJson.get("images"));
		}

		return this.parseTrack(json, preview);
	}

	/**
	 * Safely JSON-escape a string for embedding in a manually built JSON body.
	 */
	private static String jsonString(String value) {
		return "\"" + value
			.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r")
			.replace("\t", "\\t")
			+ "\"";
	}

	private List<AudioTrack> parseTracks(JsonBrowser json, boolean preview) {
		var tracks = new ArrayList<AudioTrack>();
		for (var value : json.get("tracks").values()) {
			tracks.add(this.parseTrack(value, preview));
		}
		return tracks;
	}

	private List<AudioTrack> parseTrackItems(JsonBrowser json, boolean preview) {
		var tracks = new ArrayList<AudioTrack>();
		for (var value : json.get("items").values()) {
			if (value.get("is_local").asBoolean(false)) {
				continue;
			}
			tracks.add(this.parseTrack(value, preview));
		}
		return tracks;
	}

	private AudioTrack parseTrack(JsonBrowser json, boolean preview) {
		return new SpotifyAudioTrack(
			new AudioTrackInfo(
				json.get("name").safeText(),
				json.get("artists").index(0).get("name").safeText().isEmpty() ? "Unknown" : json.get("artists").index(0).get("name").safeText(),
				preview ? PREVIEW_LENGTH : json.get("duration_ms").asLong(0),
				json.get("id").text() != null ? json.get("id").text() : "local",
				false,
				json.get("external_urls").get("spotify").text(),
				json.get("album").get("images").index(0).get("url").text(),
				json.get("external_ids").get("isrc").text()
			),
			json.get("album").get("name").text(),
			json.get("album").get("external_urls").get("spotify").text(),
			json.get("artists").index(0).get("external_urls").get("spotify").text(),
			json.get("artists").index(0).get("images").index(0).get("url").text(),
			json.get("preview_url").text(),
			preview,
			this
		);
	}

	@Override
	public void shutdown() {
		try {
			this.httpInterfaceManager.close();
		} catch (IOException e) {
			log.error("Failed to close HTTP interface manager", e);
		}
	}

	@Override
	public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
		this.httpInterfaceManager.configureRequests(configurator);
	}

	@Override
	public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
		this.httpInterfaceManager.configureBuilder(configurator);
	}
}