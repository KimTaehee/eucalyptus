/*************************************************************************
 * Copyright 2009-2015 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 ************************************************************************/
package com.eucalyptus.auth.euare.identity;

import static com.eucalyptus.util.CollectionUtils.propertyPredicate;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.apache.log4j.Logger;
import com.eucalyptus.auth.AccessKeys;
import com.eucalyptus.auth.Accounts;
import com.eucalyptus.auth.AuthException;
import com.eucalyptus.auth.InvalidAccessKeyAuthException;
import com.eucalyptus.auth.api.PrincipalProvider;
import com.eucalyptus.auth.euare.EuareException;
import com.eucalyptus.auth.euare.EuareServerCertificateUtil;
import com.eucalyptus.auth.euare.UserPrincipalImpl;
import com.eucalyptus.auth.euare.common.identity.Account;
import com.eucalyptus.auth.euare.common.identity.DecodeSecurityTokenResponseType;
import com.eucalyptus.auth.euare.common.identity.DecodeSecurityTokenResult;
import com.eucalyptus.auth.euare.common.identity.DecodeSecurityTokenType;
import com.eucalyptus.auth.euare.common.identity.DescribeAccountsResponseType;
import com.eucalyptus.auth.euare.common.identity.DescribeAccountsResult;
import com.eucalyptus.auth.euare.common.identity.DescribeAccountsType;
import com.eucalyptus.auth.euare.common.identity.DescribeCertificateResponseType;
import com.eucalyptus.auth.euare.common.identity.DescribeCertificateResult;
import com.eucalyptus.auth.euare.common.identity.DescribeCertificateType;
import com.eucalyptus.auth.euare.common.identity.DescribeInstanceProfileResponseType;
import com.eucalyptus.auth.euare.common.identity.DescribeInstanceProfileResult;
import com.eucalyptus.auth.euare.common.identity.DescribeInstanceProfileType;
import com.eucalyptus.auth.euare.common.identity.DescribePrincipalResponseType;
import com.eucalyptus.auth.euare.common.identity.DescribePrincipalResult;
import com.eucalyptus.auth.euare.common.identity.DescribePrincipalType;
import com.eucalyptus.auth.euare.common.identity.DescribeRoleResponseType;
import com.eucalyptus.auth.euare.common.identity.DescribeRoleResult;
import com.eucalyptus.auth.euare.common.identity.DescribeRoleType;
import com.eucalyptus.auth.euare.common.identity.Policy;
import com.eucalyptus.auth.euare.common.identity.Principal;
import com.eucalyptus.auth.euare.common.identity.ReserveNameResponseType;
import com.eucalyptus.auth.euare.common.identity.ReserveNameResult;
import com.eucalyptus.auth.euare.common.identity.ReserveNameType;
import com.eucalyptus.auth.euare.common.identity.SecurityToken;
import com.eucalyptus.auth.euare.common.identity.SignCertificateResponseType;
import com.eucalyptus.auth.euare.common.identity.SignCertificateResult;
import com.eucalyptus.auth.euare.common.identity.SignCertificateType;
import com.eucalyptus.auth.euare.common.identity.TunnelActionResponseType;
import com.eucalyptus.auth.euare.common.identity.TunnelActionResult;
import com.eucalyptus.auth.euare.common.identity.TunnelActionType;
import com.eucalyptus.auth.principal.AccessKey;
import com.eucalyptus.auth.principal.AccountIdentifiers;
import com.eucalyptus.auth.principal.Certificate;
import com.eucalyptus.auth.principal.InstanceProfile;
import com.eucalyptus.auth.principal.PolicyVersion;
import com.eucalyptus.auth.principal.PolicyVersions;
import com.eucalyptus.auth.principal.Role;
import com.eucalyptus.auth.principal.SecurityTokenContent;
import com.eucalyptus.auth.principal.UserPrincipal;
import com.eucalyptus.binding.Binding;
import com.eucalyptus.binding.BindingManager;
import com.eucalyptus.binding.HoldMe;
import com.eucalyptus.component.annotation.ComponentNamed;
import com.eucalyptus.component.auth.SystemCredentials;
import com.eucalyptus.component.id.Euare;
import com.eucalyptus.context.Contexts;
import com.eucalyptus.crypto.util.B64;
import com.eucalyptus.crypto.util.PEMFiles;
import com.eucalyptus.util.Exceptions;
import com.eucalyptus.util.LockResource;
import com.eucalyptus.util.TypeMapper;
import com.eucalyptus.util.TypeMappers;
import com.eucalyptus.util.async.AsyncRequests;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import edu.ucsb.eucalyptus.msgs.BaseMessage;

/**
 *
 */
@SuppressWarnings( "UnusedDeclaration" )
@ComponentNamed
public class IdentityService {

  private static final Logger logger = Logger.getLogger( IdentityService.class );

  private PrincipalProvider principalProvider;

  @Inject
  public IdentityService( @Named( "localPrincipalProvider" ) final PrincipalProvider principalProvider ) {
    this.principalProvider = principalProvider;
  }

  public DescribePrincipalResponseType describePrincipal( final DescribePrincipalType request ) throws IdentityServiceException {
    final DescribePrincipalResponseType response = request.getReply( );
    final DescribePrincipalResult result = new DescribePrincipalResult( );

    try {
      final UserPrincipal user;
      if ( request.getAccessKeyId() != null ) {
        user = principalProvider.lookupPrincipalByAccessKeyId( request.getAccessKeyId(), request.getNonce() );
      } else if ( request.getCertificateId() != null ) {
        user = principalProvider.lookupPrincipalByCertificateId( request.getCertificateId() );
      } else if ( request.getUserId( ) != null ) {
        user = principalProvider.lookupPrincipalByUserId( request.getUserId( ), request.getNonce( ) );
      } else if ( request.getRoleId( ) != null ) {
        user = principalProvider.lookupPrincipalByRoleId( request.getRoleId( ), request.getNonce( ) );
      } else if ( request.getAccountId( ) != null && request.getUsername( ) != null ) {
        user = principalProvider
            .lookupPrincipalByAccountNumberAndUsername( request.getAccountId(), request.getUsername() );
      } else if ( request.getAccountId( ) != null ) {
        user = principalProvider.lookupPrincipalByAccountNumber( request.getAccountId( ) );
      } else if ( request.getCanonicalId( ) != null ) {
        user = principalProvider.lookupPrincipalByCanonicalId( request.getCanonicalId( ) );
      } else {
        user = null;
      }

      if ( user != null ) {
        final String ptag = UserPrincipalImpl.ptag( user );
        final Principal principal = new Principal( );
        principal.setArn( Accounts.getUserArn( user ) );
        principal.setUserId( user.getUserId( ) );
        principal.setRoleId( Accounts.isRoleIdentifier( user.getAuthenticatedId( ) ) ?
                user.getAuthenticatedId( ) :
                null
        );
        principal.setCanonicalId( user.getCanonicalId( ) );
        principal.setPtag( ptag );
        if ( !ptag.equals( request.getPtag( ) ) ) {
          principal.setAccountAlias( user.getAccountAlias() );
          principal.setEnabled( user.isEnabled( ) );
          principal.setPasswordHash( user.getPassword( ) );
          principal.setPasswordExpiry( user.getPasswordExpires( ) );

          final ArrayList<com.eucalyptus.auth.euare.common.identity.AccessKey> accessKeys = Lists.newArrayList( );
          for ( final AccessKey accessKey : Iterables.filter( user.getKeys( ), AccessKeys.isActive( ) ) ) {
            final com.eucalyptus.auth.euare.common.identity.AccessKey key =
                new com.eucalyptus.auth.euare.common.identity.AccessKey( );
            key.setAccessKeyId( accessKey.getAccessKey( ) );
            key.setSecretAccessKey( accessKey.getSecretKey( ) );
            accessKeys.add( key );
          }
          principal.setAccessKeys( accessKeys );

          final ArrayList<com.eucalyptus.auth.euare.common.identity.Certificate> certificates = Lists.newArrayList( );
          for ( final Certificate certificate :
              Iterables.filter( user.getCertificates( ), propertyPredicate( true, Certificate.Util.active( ) ) ) ) {
            final com.eucalyptus.auth.euare.common.identity.Certificate cert =
                new com.eucalyptus.auth.euare.common.identity.Certificate();
            cert.setCertificateId( certificate.getCertificateId() );
            cert.setCertificateBody( certificate.getPem() );
            certificates.add( cert );
          }
          principal.setCertificates( certificates );

          final ArrayList<Policy> policies = Lists.newArrayList( );
          if ( user.isEnabled( ) ) {
            Iterables.addAll(
                policies,
                Iterables.transform(
                    user.getPrincipalPolicies( ),
                    TypeMappers.lookup( PolicyVersion.class, Policy.class ) ) );
          }
          principal.setPolicies( policies );
        }

        result.setPrincipal( principal );
      }
    } catch ( InvalidAccessKeyAuthException e ) {
      // not found, so empty response
    } catch ( AuthException e ) {
      throw handleException( e );
    }

    response.setDescribePrincipalResult( result );
    return response;
  }

  public DescribeAccountsResponseType describeAccounts(
    final DescribeAccountsType request
  ) throws IdentityServiceException {
    final DescribeAccountsResponseType response = request.getReply( );
    final DescribeAccountsResult result = new DescribeAccountsResult( );

    try {
      final Iterable<AccountIdentifiers> accountIdentifiers;
      if ( request.getAlias() != null ) {
        accountIdentifiers =
            Collections.singleton( principalProvider.lookupAccountIdentifiersByAlias( request.getAlias() ) );
      } else if ( request.getCanonicalId() != null ) {
        accountIdentifiers =
            Collections.singleton( principalProvider.lookupAccountIdentifiersByCanonicalId( request.getCanonicalId() ) );
      } else if ( request.getEmail() != null ) {
        accountIdentifiers =
            Collections.singleton( principalProvider.lookupAccountIdentifiersByEmail( request.getEmail() ) );
      } else if ( request.getAliasLike() != null ) {
        accountIdentifiers = principalProvider.listAccountIdentifiersByAliasMatch( request.getAliasLike() );
      } else {
        accountIdentifiers = null;
      }

      final ArrayList<Account> accounts = Lists.newArrayList( );
      if ( accountIdentifiers != null ) {
        Iterables.addAll(
            accounts,
            Iterables.transform( accountIdentifiers, TypeMappers.lookup( AccountIdentifiers.class, Account.class ) ) );
      }
      result.setAccounts( accounts );
    } catch ( AuthException e ) {
      throw handleException( e );
    }

    response.setDescribeAccountsResult( result );
    return response;
  }

  public DescribeInstanceProfileResponseType describeInstanceProfile(
      final DescribeInstanceProfileType request
  ) throws IdentityServiceException {
    final DescribeInstanceProfileResponseType response = request.getReply( );
    final DescribeInstanceProfileResult result = new DescribeInstanceProfileResult( );

    try {
      final InstanceProfile instanceProfile =
          principalProvider.lookupInstanceProfileByName( request.getAccountId( ), request.getInstanceProfileName( ) );
      result.setInstanceProfile(
          TypeMappers.transform( instanceProfile, com.eucalyptus.auth.euare.common.identity.InstanceProfile.class ) );
      result.setRole(
          TypeMappers.transform( instanceProfile.getRole(), com.eucalyptus.auth.euare.common.identity.Role.class ) );
    } catch ( AuthException e ) {
      throw handleException( e );
    }

    response.setDescribeInstanceProfileResult( result );
    return response;
  }

  public DescribeRoleResponseType describeRole( final DescribeRoleType request ) throws IdentityServiceException {
    final DescribeRoleResponseType response = request.getReply( );
    final DescribeRoleResult result = new DescribeRoleResult( );

    try {
      final Role role = principalProvider.lookupRoleByName( request.getAccountId(), request.getRoleName() );
      result.setRole(
          TypeMappers.transform( role, com.eucalyptus.auth.euare.common.identity.Role.class ) );
    } catch ( AuthException e ) {
      throw handleException( e );
    }

    response.setDescribeRoleResult( result );
    return response;
  }

  public DecodeSecurityTokenResponseType decodeSecurityToken(
      final DecodeSecurityTokenType request
  ) throws IdentityServiceException {
    final DecodeSecurityTokenResponseType response = request.getReply( );
    final DecodeSecurityTokenResult result = new DecodeSecurityTokenResult( );

    try {
      final SecurityTokenContent securityTokenContent =
          principalProvider.decodeSecurityToken( request.getAccessKeyId(), request.getSecurityToken() );
      result.setSecurityToken(
          TypeMappers.transform( securityTokenContent, SecurityToken.class ) );
    } catch ( AuthException e ) {
      throw handleException( e );
    }

    response.setDecodeSecurityTokenResult( result );
    return response;
  }

  public ReserveNameResponseType reserveName(
      final ReserveNameType request
  ) throws IdentityServiceException {
    final ReserveNameResponseType response = request.getReply( );
    final ReserveNameResult result = new ReserveNameResult( );

    try {
      principalProvider.reserveGlobalName( request.getNamespace(), request.getName(), request.getDuration(), request.getClientToken() );
    } catch ( AuthException e ) {
      if ( AuthException.CONFLICT.equals( e.getMessage( ) ) ) {
        throw new IdentityServiceSenderException( "Conflict", "Name not available: " + request.getName( ) );
      }
      throw handleException( e );
    }

    response.setReserveNameResult( result );
    return response;
  }

  public DescribeCertificateResponseType describeCertificate( final DescribeCertificateType request ) {
    final DescribeCertificateResponseType response = request.getReply( );
    final DescribeCertificateResult result = new DescribeCertificateResult( );
    result.setPem( new String(
        PEMFiles.getBytes( SystemCredentials.lookup( Euare.class ).getCertificate( ) ),
        StandardCharsets.UTF_8 ) );
    response.setDescribeCertificateResult( result );
    return response;
  }

  public SignCertificateResponseType signCertificate( final SignCertificateType request ) throws IdentityServiceException {
    final SignCertificateResponseType response = request.getReply( );
    final SignCertificateResult result = new SignCertificateResult( );
    final String pubkey = request.getKey( );
    final String principal = request.getPrincipal( );
    final Integer expirationDays = request.getExpirationDays( );

    if( Strings.isNullOrEmpty( pubkey ) )
      throw new IdentityServiceSenderException( "InvalidValue", "No public key is provided");
    if( Strings.isNullOrEmpty( principal ) )
      throw new IdentityServiceSenderException( "InvalidValue", "No principal is provided");

    try {
      final KeyFactory keyFactory = KeyFactory.getInstance( "RSA", "BC");
      final X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec( B64.standard.dec( pubkey ) );
      final PublicKey publicKey = keyFactory.generatePublic( publicKeySpec );
      final X509Certificate vmCert = EuareServerCertificateUtil.generateVMCertificate(
          (RSAPublicKey) publicKey,
          principal,
          Objects.firstNonNull( expirationDays, 180 )
      );
      final String certPem = new String( PEMFiles.getBytes(  vmCert ) );
      result.setPem( certPem );
    } catch( final Exception ex ) {
      throw new IdentityServiceReceiverException( "InternalError", String.valueOf( ex.getMessage( ) ));
    }
    response.setSignCertificateResult( result );
    return response;
  }


  public TunnelActionResponseType tunnelAction( final TunnelActionType request ) throws IdentityServiceException {
    final TunnelActionResponseType response = request.getReply( );
    final TunnelActionResult result = new TunnelActionResult( );

    try {
      final String content = request.getContent( );
      final Binding binding = BindingManager.getDefaultBinding( );
      final BaseMessage euareRequest;
      try ( final LockResource lock = LockResource.lock( HoldMe.canHas ) ) {
        final StAXOMBuilder omBuilder = HoldMe.getStAXOMBuilder( HoldMe.getXMLStreamReader( content ) );
        final OMElement message = omBuilder.getDocumentElement( );
        final Class<?> messageType = binding.getElementClass( message.getLocalName() );
        euareRequest = (BaseMessage) binding.fromOM( message, messageType ); //TODO:STEVE: allow for (subminor?) version differences
      }
      final BaseMessage euareResponse = AsyncRequests.sendSync( Euare.class, euareRequest );
      final StringWriter writer = new StringWriter( );
      try ( final LockResource lock = LockResource.lock( HoldMe.canHas ) ) {
        final OMElement message = binding.toOM( euareResponse );
        final OMOutputFormat format = new OMOutputFormat( );
        format.setIgnoreXMLDeclaration( true );
        message.serialize( writer );
      }
      result.setContent( writer.toString( ) );
    } catch ( Exception e ) {
      @SuppressWarnings( "ThrowableResultOfMethodCallIgnored" )
      final EuareException euareException = Exceptions.findCause( e, EuareException.class );
      if ( euareException != null ) {
        throw new IdentityServiceStatusException(
            euareException.getError( ),
            euareException.getStatus( ).getCode( ),
            euareException.getMessage( ) );
      }
      throw handleException( e );
    }

    response.setTunnelActionResult( result );
    return response;
  }

  /**
   * Method always throws, signature allows use of "throw handleException ..."
   */
  private IdentityServiceException handleException( final Exception e ) throws IdentityServiceException {
    final IdentityServiceException cause = Exceptions.findCause( e, IdentityServiceException.class );
    if ( cause != null ) {
      throw cause;
    }

    logger.error( e, e );

    final IdentityServiceException exception =
        new IdentityServiceReceiverException( "InternalError", String.valueOf( e.getMessage( ) ) );
    if ( Contexts.lookup( ).hasAdministrativePrivileges( ) ) {
      exception.initCause( e );
    }
    throw exception;
  }

  @TypeMapper
  public enum PolicyVersionToPolicyTransform implements Function<PolicyVersion,Policy> {
    INSTANCE;

    @Nullable
    @Override
    public Policy apply( final PolicyVersion policyVersion ) {
      final Policy policy = new Policy();
      policy.setVersionId( policyVersion.getPolicyVersionId() );
      policy.setName( policyVersion.getPolicyName() );
      policy.setScope( policyVersion.getPolicyScope().toString() );
      policy.setPolicy( policyVersion.getPolicy() );
      policy.setHash( PolicyVersions.hash( policyVersion.getPolicy( ) ) );
      return policy;
    }
  }

  @TypeMapper
  public enum AccountIdentifiersToAccountTransform implements Function<AccountIdentifiers,Account> {
    INSTANCE;

    @Nullable
    @Override
    public Account apply( final AccountIdentifiers accountIdentifiers ) {
      final Account account = new Account( );
      account.setAccountNumber( accountIdentifiers.getAccountNumber() );
      account.setAlias( accountIdentifiers.getAccountAlias() );
      account.setCanonicalId( accountIdentifiers.getCanonicalId() );
      return account;
    }
  }

  @TypeMapper
  public enum InstanceProfileToInstanceProfileTransform
      implements Function<InstanceProfile,com.eucalyptus.auth.euare.common.identity.InstanceProfile> {
    INSTANCE;

    @Nullable
    @Override
    public com.eucalyptus.auth.euare.common.identity.InstanceProfile apply( final InstanceProfile authProfile ) {
      final com.eucalyptus.auth.euare.common.identity.InstanceProfile profile =
          new com.eucalyptus.auth.euare.common.identity.InstanceProfile( );
      profile.setInstanceProfileArn( authProfile.getInstanceProfileArn() );
      profile.setInstanceProfileId( authProfile.getInstanceProfileId() );
      return profile;
    }
  }

  @TypeMapper
  public enum RoleToRoleTransform implements Function<Role,com.eucalyptus.auth.euare.common.identity.Role> {
    INSTANCE;

    @Nullable
    @Override
    public com.eucalyptus.auth.euare.common.identity.Role apply( final Role authRole ) {
      final com.eucalyptus.auth.euare.common.identity.Role role = new com.eucalyptus.auth.euare.common.identity.Role( );
      role.setRoleArn( authRole.getRoleArn( ) );
      role.setRoleId( authRole.getRoleId( ) );
      role.setSecret( authRole.getSecret() );
      role.setAssumeRolePolicy( TypeMappers.transform( authRole.getPolicy(), Policy.class ) );
      return role;
    }
  }

  @TypeMapper
  public enum SecurityTokenContentToSecurityTokenTransform implements Function<SecurityTokenContent,SecurityToken> {
    INSTANCE;

    @Nullable
    @Override
    public SecurityToken apply( final SecurityTokenContent securityTokenContent ) {
      final SecurityToken securityToken = new SecurityToken( );
      securityToken.setOriginatingAccessKeyId( securityTokenContent.getOriginatingAccessKeyId( ).orNull( ) );
      securityToken.setOriginatingUserId( securityTokenContent.getOriginatingUserId( ).orNull( ) );
      securityToken.setOriginatingRoleId( securityTokenContent.getOriginatingRoleId( ).orNull( ) );
      securityToken.setNonce( securityTokenContent.getNonce( ) );
      securityToken.setCreated( securityTokenContent.getCreated( ) );
      securityToken.setExpires( securityTokenContent.getExpires( ) );

      return securityToken;
    }
  }
}
