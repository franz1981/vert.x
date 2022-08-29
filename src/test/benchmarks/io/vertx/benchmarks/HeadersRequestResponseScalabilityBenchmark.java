package io.vertx.benchmarks;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.AsciiString;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 200, timeUnit = TimeUnit.MILLISECONDS)
public class HeadersRequestResponseScalabilityBenchmark extends BenchmarkBase {

  // this is not benchmarking efficiency of hash codes, but just what happen by mixing CharSequence types
  // These 3s are coming from Netty HttpObjectEncoder::readHeaders
  private CharSequence host;
  private CharSequence localhost;
  private CharSequence connection;
  private CharSequence keepAlive;
  private CharSequence accept;
  private CharSequence acceptedTypes;

  private AsciiString contentLengthAscii;

  private CharSequence serverAscii;

  private CharSequence quarkusAscii;
  private CharSequence dateAscii;

  private CharSequence dateValueAscii;

  private CharSequence length;

  private String contentType;

  private String plainTextUtf8;

  @Setup
  public void setup() {
    host = "Host";
    localhost = "localhost";
    connection = "Connection";
    keepAlive = "keep-alive";
    accept = "Accept";
    acceptedTypes = "text/plain,text/html;q=0.9,application/xhtml+xml;q=0.9,application/xml;q=0.8,*/*;q=0.7";
    contentLengthAscii = new AsciiString("content-length");

    serverAscii = new AsciiString("Server");
    quarkusAscii = new AsciiString("Quarkus");
    dateAscii = new AsciiString("Date");
    dateValueAscii = new AsciiString("Tue, 3 Jun 2008 11:05:30 GMT");
    length = "12";
    contentType = "Content-Type";
    plainTextUtf8 = "text/plain;charset=UTF-8";
  }

  private void requestAdd(HttpHeaders requestHeaders) {
    requestHeaders.add(host, localhost);
    requestHeaders.add(connection, keepAlive);
    requestHeaders.add(accept, acceptedTypes);
  }

  private void requestGetAll(HttpHeaders requestHeaders, Blackhole bh) {
    bh.consume(requestHeaders.getAll(contentLengthAscii));
    bh.consume(requestHeaders.getAll(accept));
  }

  private void responseAdd(HttpHeaders responseHeaders) {
    responseHeaders.add(serverAscii, quarkusAscii);
    responseHeaders.add(dateAscii, dateValueAscii);
  }

  private void responseSet(HttpHeaders responseHeaders) {
    responseHeaders.set(contentLengthAscii, length);
    responseHeaders.set(contentType, plainTextUtf8);
  }


  @Benchmark
  public void addGetAllRemoveString(Blackhole bh) {
    final HttpHeaders requestHeaders = HeadersMultiMap.httpHeaders();
    requestAdd(requestHeaders);
    requestGetAll(requestHeaders, bh);
    bh.consume(requestHeaders);

    final HttpHeaders responseHeaders = HeadersMultiMap.httpHeaders();
    responseAdd(responseHeaders);
    responseSet(responseHeaders);
    bh.consume(responseHeaders);
  }

}
