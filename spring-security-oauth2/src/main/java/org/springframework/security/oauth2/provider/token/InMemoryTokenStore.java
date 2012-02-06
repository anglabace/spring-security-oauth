package org.springframework.security.oauth2.provider.token;

import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.security.oauth2.common.ExpiringOAuth2RefreshToken;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.util.Assert;

/**
 * Implementation of token services that stores tokens in memory.
 * 
 * @author Ryan Heaton
 * @author Luke Taylor
 * @author Dave Syer
 */
public class InMemoryTokenStore implements TokenStore {

	private static final int DEFAULT_FLUSH_INTERVAL = 1000;

	private final ConcurrentHashMap<String, OAuth2AccessToken> accessTokenStore = new ConcurrentHashMap<String, OAuth2AccessToken>();

	private final ConcurrentHashMap<String, OAuth2AccessToken> authenticationToAccessTokenStore = new ConcurrentHashMap<String, OAuth2AccessToken>();

	private final ConcurrentHashMap<String, ExpiringOAuth2RefreshToken> refreshTokenStore = new ConcurrentHashMap<String, ExpiringOAuth2RefreshToken>();

	private final ConcurrentHashMap<String, String> accessTokenToRefreshTokenStore = new ConcurrentHashMap<String, String>();

	private final ConcurrentHashMap<String, OAuth2Authentication> authenticationStore = new ConcurrentHashMap<String, OAuth2Authentication>();

	private final ConcurrentHashMap<String, String> refreshTokenToAcessTokenStore = new ConcurrentHashMap<String, String>();

	private final DelayQueue<TokenExpiry> expiryQueue = new DelayQueue<TokenExpiry>();

	private int flushInterval = DEFAULT_FLUSH_INTERVAL;

	private AuthenticationKeyGenerator authenticationKeyGenerator = new DefaultAuthenticationKeyGenerator();

	private AtomicInteger flushCounter = new AtomicInteger(0);

	/**
	 * The number of tokens to store before flushing expired tokens. Defaults to 1000.
	 * 
	 * @param flushInterval the interval to set
	 */
	public void setFlushInterval(int flushInterval) {
		this.flushInterval = flushInterval;
	}

	/**
	 * The interval (count of token inserts) between flushing expired tokens.
	 * 
	 * @return the flushInterval the flush interval
	 */
	public int getFlushInterval() {
		return flushInterval;
	}

	public void setAuthenticationKeyGenerator(AuthenticationKeyGenerator authenticationKeyGenerator) {
		this.authenticationKeyGenerator = authenticationKeyGenerator;
	}

	public int getAccessTokenCount() {
		Assert.state(accessTokenStore.size() >= accessTokenToRefreshTokenStore.size(), "Too many refresh tokens");
		Assert.state(accessTokenStore.size() == authenticationToAccessTokenStore.size(),
				"Inconsistent token store state");
		Assert.state(accessTokenStore.size() <= authenticationStore.size(), "Inconsistent authentication store state");
		return accessTokenStore.size();
	}

	public int getRefreshTokenCount() {
		Assert.state(refreshTokenStore.size() == refreshTokenToAcessTokenStore.size(),
				"Inconsistent refresh token store state");
		return accessTokenStore.size();
	}

	public OAuth2AccessToken getAccessToken(OAuth2Authentication authentication) {
		OAuth2AccessToken accessToken = authenticationToAccessTokenStore.get(authenticationKeyGenerator.extractKey(authentication));
		if (accessToken!=null && !authentication.equals(readAuthentication(accessToken))) {
			// Keep the stores consistent (maybe the same user is represented by this authentication but the details have changed)
			storeAccessToken(accessToken, authentication);
		}
		return accessToken;
	}

	public OAuth2Authentication readAuthentication(OAuth2AccessToken token) {
		return this.authenticationStore.get(token.getValue());
	}

	public void storeAccessToken(OAuth2AccessToken token, OAuth2Authentication authentication) {
		if (this.flushCounter.incrementAndGet() >= this.flushInterval) {
			flush();
			this.flushCounter.set(0);
		}
		this.accessTokenStore.put(token.getValue(), token);
		this.authenticationStore.put(token.getValue(), authentication);
		this.authenticationToAccessTokenStore.put(authenticationKeyGenerator.extractKey(authentication), token);
		if (token.getExpiration() != null) {
			this.expiryQueue.put(new TokenExpiry(token.getValue(), token.getExpiration()));
		}
		if (token.getRefreshToken() != null && token.getRefreshToken().getValue() != null) {
			this.refreshTokenToAcessTokenStore.put(token.getRefreshToken().getValue(), token.getValue());
			this.accessTokenToRefreshTokenStore.put(token.getValue(), token.getRefreshToken().getValue());
		}
	}

	public OAuth2AccessToken readAccessToken(String tokenValue) {
		return this.accessTokenStore.get(tokenValue);
	}

	public void removeAccessToken(String tokenValue) {
		this.accessTokenStore.remove(tokenValue);
		String refresh = this.accessTokenToRefreshTokenStore.remove(tokenValue);
		if (refresh != null) {
			this.refreshTokenStore.remove(tokenValue);
			this.refreshTokenToAcessTokenStore.remove(tokenValue);
		}
		OAuth2Authentication authentication = this.authenticationStore.remove(tokenValue);
		if (authentication != null) {
			this.authenticationToAccessTokenStore.remove(authenticationKeyGenerator.extractKey(authentication));
		}
	}

	public OAuth2Authentication readAuthentication(ExpiringOAuth2RefreshToken token) {
		return this.authenticationStore.get(token.getValue());
	}

	public void storeRefreshToken(ExpiringOAuth2RefreshToken refreshToken, OAuth2Authentication authentication) {
		this.refreshTokenStore.put(refreshToken.getValue(), refreshToken);
		this.authenticationStore.put(refreshToken.getValue(), authentication);
	}

	public ExpiringOAuth2RefreshToken readRefreshToken(String tokenValue) {
		return this.refreshTokenStore.get(tokenValue);
	}

	public void removeRefreshToken(String tokenValue) {
		this.refreshTokenStore.remove(tokenValue);
		this.authenticationStore.remove(tokenValue);
	}

	public void removeAccessTokenUsingRefreshToken(String refreshToken) {
		String accessToken = this.refreshTokenToAcessTokenStore.remove(refreshToken);
		if (accessToken != null) {
			this.accessTokenStore.remove(accessToken);
			this.authenticationStore.remove(accessToken);
		}
	}

	private void flush() {
		TokenExpiry expiry = expiryQueue.poll();
		while (expiry != null) {
			removeAccessToken(expiry.getValue());
			expiry = expiryQueue.poll();
		}
	}

	private static class TokenExpiry implements Delayed {

		private final long expiry;

		private final String value;

		public TokenExpiry(String value, Date date) {
			this.value = value;
			this.expiry = date.getTime();
		}

		public int compareTo(Delayed other) {
			if (this == other) {
				return 0;
			}
			long diff = getDelay(TimeUnit.MILLISECONDS) - other.getDelay(TimeUnit.MILLISECONDS);
			return (diff == 0 ? 0 : ((diff < 0) ? -1 : 1));
		}

		public long getDelay(TimeUnit unit) {
			return expiry - System.currentTimeMillis();
		}

		public String getValue() {
			return value;
		}

	}

}
