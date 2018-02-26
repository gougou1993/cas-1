package org.apereo.cas.support.oauth.web.endpoints;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.authentication.principal.ServiceFactory;
import org.apereo.cas.authentication.principal.WebApplicationService;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.support.oauth.OAuth20Constants;
import org.apereo.cas.support.oauth.profile.OAuth20ProfileScopeToAttributesFilter;
import org.apereo.cas.support.oauth.profile.OAuth2UserProfileDataCreator;
import org.apereo.cas.support.oauth.util.OAuth20Utils;
import org.apereo.cas.support.oauth.validator.OAuth20Validator;
import org.apereo.cas.support.oauth.web.views.OAuth20UserProfileViewRenderer;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.ticket.TicketState;
import org.apereo.cas.ticket.accesstoken.AccessToken;
import org.apereo.cas.ticket.accesstoken.AccessTokenFactory;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.apereo.cas.util.Pac4jUtils;
import org.apereo.cas.web.support.CookieRetrievingCookieGenerator;
import org.pac4j.core.context.HttpConstants;
import org.pac4j.core.context.J2EContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * This controller returns a profile for the authenticated user
 * (identifier + attributes), found with the access token.
 *
 * @author Jerome Leleu
 * @since 3.5.0
 */
@Slf4j
public class OAuth20UserProfileEndpointController extends BaseOAuth20Controller {
    
    /**
     * View renderer for the final profile.
     */
    private final OAuth20UserProfileViewRenderer userProfileViewRenderer;

    /**
     * User profile data creator.
     */
    private final OAuth2UserProfileDataCreator userProfileDataCreator;
            
    public OAuth20UserProfileEndpointController(final ServicesManager servicesManager,
                                                final TicketRegistry ticketRegistry,
                                                final OAuth20Validator validator,
                                                final AccessTokenFactory accessTokenFactory,
                                                final PrincipalFactory principalFactory,
                                                final ServiceFactory<WebApplicationService> webApplicationServiceServiceFactory,
                                                final OAuth20ProfileScopeToAttributesFilter scopeToAttributesFilter,
                                                final CasConfigurationProperties casProperties,
                                                final CookieRetrievingCookieGenerator cookieGenerator,
                                                final OAuth20UserProfileViewRenderer userProfileViewRenderer,
                                                final OAuth2UserProfileDataCreator userProfileDataCreator) {
        super(servicesManager, ticketRegistry, validator, accessTokenFactory, principalFactory,
                webApplicationServiceServiceFactory, scopeToAttributesFilter, casProperties, cookieGenerator);
        this.userProfileViewRenderer = userProfileViewRenderer;
        this.userProfileDataCreator = userProfileDataCreator;
    }

    /**
     * Handle request internal response entity.
     *
     * @param request  the request
     * @param response the response
     * @return the response entity
     * @throws Exception the exception
     */
    @GetMapping(path = OAuth20Constants.BASE_OAUTH20_URL + '/' + OAuth20Constants.PROFILE_URL, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handleRequest(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        final J2EContext context = Pac4jUtils.getPac4jJ2EContext(request, response);
        
        final String accessToken = getAccessTokenFromRequest(request);
        if (StringUtils.isBlank(accessToken)) {
            LOGGER.error("Missing [{}]", OAuth20Constants.ACCESS_TOKEN);
            return buildUnauthorizedResponseEntity(OAuth20Constants.MISSING_ACCESS_TOKEN);
        }

        final AccessToken accessTokenTicket = this.ticketRegistry.getTicket(accessToken, AccessToken.class);
        if (accessTokenTicket == null || accessTokenTicket.isExpired()) {
            LOGGER.error("Expired/Missing access token: [{}]", accessToken);
            return buildUnauthorizedResponseEntity(OAuth20Constants.EXPIRED_ACCESS_TOKEN);
        }

        if (casProperties.getLogout().isRemoveDescendantTickets()) {
            final TicketGrantingTicket ticketGrantingTicket = accessTokenTicket.getTicketGrantingTicket();
            if (ticketGrantingTicket == null || ticketGrantingTicket.isExpired()) {
                LOGGER.error("Ticket granting ticket [{}] parenting access token [{}] has expired or is not found", ticketGrantingTicket, accessTokenTicket);
                this.ticketRegistry.deleteTicket(accessToken);
                return buildUnauthorizedResponseEntity(OAuth20Constants.EXPIRED_ACCESS_TOKEN);
            }
        }
        updateAccessTokenUsage(accessTokenTicket);

        final Map<String, Object> map = this.userProfileDataCreator.createFrom(accessTokenTicket, context);
        final String value = this.userProfileViewRenderer.render(map, accessTokenTicket);
        return new ResponseEntity<>(value, HttpStatus.OK);
    }

    private void updateAccessTokenUsage(final AccessToken accessTokenTicket) {
        final TicketState accessTokenState = TicketState.class.cast(accessTokenTicket);
        accessTokenState.update();
        if (accessTokenTicket.isExpired()) {
            this.ticketRegistry.deleteTicket(accessTokenTicket.getId());
        } else {
            this.ticketRegistry.updateTicket(accessTokenTicket);
        }
    }

    /**
     * Gets access token from request.
     *
     * @param request the request
     * @return the access token from request
     */
    protected String getAccessTokenFromRequest(final HttpServletRequest request) {
        String accessToken = request.getParameter(OAuth20Constants.ACCESS_TOKEN);
        if (StringUtils.isBlank(accessToken)) {
            final String authHeader = request.getHeader(HttpConstants.AUTHORIZATION_HEADER);
            if (StringUtils.isNotBlank(authHeader) && authHeader.toLowerCase().startsWith(OAuth20Constants.BEARER_TOKEN.toLowerCase() + ' ')) {
                accessToken = authHeader.substring(OAuth20Constants.BEARER_TOKEN.length() + 1);
            }
        }
        LOGGER.debug("[{}]: [{}]", OAuth20Constants.ACCESS_TOKEN, accessToken);
        return accessToken;
    }

    /**
     * Build unauthorized response entity.
     *
     * @param code the code
     * @return the response entity
     */
    private static ResponseEntity buildUnauthorizedResponseEntity(final String code) {
        final LinkedMultiValueMap<String, String> map = new LinkedMultiValueMap<>(1);
        map.add(OAuth20Constants.ERROR, code);
        final String value = OAuth20Utils.jsonify(map);
        return new ResponseEntity<>(value, HttpStatus.UNAUTHORIZED);
    }
}
