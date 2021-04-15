/*
 * MIT License
 *
 * Copyright 2021 Myndigheten för digital förvaltning (DIGG)
 */
package se.digg.dgc.service.impl;

import java.io.IOException;
import java.security.SignatureException;
import java.security.cert.CertificateExpiredException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upokecenter.cbor.CBORException;

import se.digg.dgc.encoding.BarcodeDecoder;
import se.digg.dgc.encoding.BarcodeException;
import se.digg.dgc.encoding.Base45;
import se.digg.dgc.encoding.DGCConstants;
import se.digg.dgc.encoding.Zlib;
import se.digg.dgc.encoding.impl.DefaultBarcodeDecoder;
import se.digg.dgc.payload.v1.DGCSchemaException;
import se.digg.dgc.payload.v1.DigitalGreenCertificate;
import se.digg.dgc.payload.v1.MapperUtils;
import se.digg.dgc.service.DGCDecoder;
import se.digg.dgc.signatures.CertificateProvider;
import se.digg.dgc.signatures.DGCSignatureVerifier;
import se.digg.dgc.signatures.impl.DefaultDGCSignatureVerifier;

/**
 * A bean implementing the {@link DGCDecoder} interface.
 * 
 * @author Martin Lindström (martin@idsec.se)
 * @author Henrik Bengtsson (extern.henrik.bengtsson@digg.se)
 * @author Henric Norlander (extern.henric.norlander@digg.se)
 */
public class DefaultDGCDecoder implements DGCDecoder {

  /** Logger */
  private static final Logger log = LoggerFactory.getLogger(DefaultDGCDecoder.class);

  /** The DGC signature verifier. */
  private final DGCSignatureVerifier dgcSignatureVerifier;

  /** The certificate provider for locating certificates for use when verifying signatures. */
  private final CertificateProvider certificateProvider;

  /** The barcode decoder. */
  private BarcodeDecoder barcodeDecoder = new DefaultBarcodeDecoder();

  /**
   * Constructor.
   * 
   * @param dgcSignatureVerifier
   *          the signature verifier - if null, an instance of {@link DefaultDGCSignatureVerifier} will be used
   * @param certificateProvider
   *          the certificate provider that is used to locate certificates to use when verifying signatures
   */
  public DefaultDGCDecoder(final DGCSignatureVerifier dgcSignatureVerifier, final CertificateProvider certificateProvider) {
    this.dgcSignatureVerifier = Optional.ofNullable(dgcSignatureVerifier)
      .orElse(new DefaultDGCSignatureVerifier());

    this.certificateProvider = Optional.ofNullable(certificateProvider)
      .orElseThrow(() -> new IllegalArgumentException("certificateProvider must be supplied"));
  }

  /** {@inheritDoc} */
  @Override
  public DigitalGreenCertificate decodeBarcode(final byte[] image)
      throws DGCSchemaException, SignatureException, CertificateExpiredException, BarcodeException, IOException {

    final byte[] encodedDgc = this.decodeBarcodeToBytes(image);
    
    log.trace("CBOR decoding DGC ...");
    final DigitalGreenCertificate dgc = MapperUtils.toDigitalGreenCertificate(encodedDgc);
    log.trace("Decoded into: {}", dgc);
    
    return dgc;
  }

  /** {@inheritDoc} */
  @Override
  public byte[] decodeBarcodeToBytes(final byte[] image)
      throws SignatureException, CertificateExpiredException, BarcodeException, IOException {

    // Get the barcode from the image and decode it ...
    //
    log.trace("Decoding barcode image ...");
    String base45 = this.barcodeDecoder.decodeToString(image, null);
    log.trace("Decoded barcode image into {}", base45);

    // Strip header ...
    //
    if (base45.startsWith(DGCConstants.DGC_V1_HEADER)) {
      base45 = base45.substring(DGCConstants.DGC_V1_HEADER.length());
      log.trace("Stripped {} header - Base45 encoding is {} characters long", DGCConstants.DGC_V1_HEADER, base45.length());
    }
    else {
      log.info("Missing header - {}", DGCConstants.DGC_V1_HEADER);
    }

    // Base45 decode into a compressed CWT ...
    //
    log.trace("Base45 decoding into a compressed CWT ...");
    final byte[] compressedCwt = Base45.getDecoder().decode(base45);
    log.trace("Compressed CWT is {} bytes long", compressedCwt.length);

    // De-compress
    //
    log.trace("De-compressing the CWT ...");
    if (!Zlib.isCompressed(compressedCwt)) {
      log.info("The data to inflate is missing ZLIB header byte - assuming un-compressed data");
    }
    final byte[] cwt = Zlib.decompress(compressedCwt, false);
    log.trace("Inflated data into CWT of length {}", cwt.length);

    // OK, we now have the uncompressed CWT, lets verify it ...
    return this.decodeRawToBytes(cwt);
  }

  /** {@inheritDoc} */
  @Override
  public DigitalGreenCertificate decodeRaw(final byte[] cwt)
      throws DGCSchemaException, SignatureException, CertificateExpiredException, IOException {

    final byte[] encodedDgc = this.decodeRawToBytes(cwt);
    
    log.trace("CBOR decoding DGC ...");
    final DigitalGreenCertificate dgc = MapperUtils.toDigitalGreenCertificate(encodedDgc);
    log.trace("Decoded into: {}", dgc);
    
    return dgc;
  }

  /** {@inheritDoc} */
  @Override
  public byte[] decodeRawToBytes(final byte[] cwt) throws SignatureException, CertificateExpiredException, IOException {

    try {
      log.trace("Verifying signature on signed CWT ...");
      final DGCSignatureVerifier.Result result = this.dgcSignatureVerifier.verify(cwt, this.certificateProvider);

      log.debug("Successful signature validation of signed CWT. dgc-length='{}', issuing-country='{}', issued-at='{}', expires='{}'",
        result.getDgcPayload().length, result.getCountry(), result.getIssuedAt(), result.getExpires());
      log.trace("Subject DN of certificate used to verify signature: {}", result.getSignerCertificate()
        .getSubjectX500Principal()
        .toString());

      return result.getDgcPayload();
    }
    catch (final CBORException e) {
      throw new IOException("CBOR error - " + e.getMessage(), e);
    }
  }

  /**
   * Assigns the barcode decoder to use.
   * <p>
   * The default is {@link DefaultBarcodeDecoder}.
   * </p>
   * 
   * @param barcodeDecoder
   *          the decoder to use
   */
  public void setBarcodeDecoder(final BarcodeDecoder barcodeDecoder) {
    if (barcodeDecoder != null) {
      this.barcodeDecoder = barcodeDecoder;
    }
  }

}
