package com.example.finance_planning.network

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/** DNSE has several documented list/object variants; decoding stays in its bounded adapter. */
internal interface DnseReadEndpoints {
    @Streaming @GET("accounts") suspend fun accounts(@HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext): Response<ResponseBody>
    @Streaming @GET("accounts/{account}/balances") suspend fun balances(@Path("account") account: String, @HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext): Response<ResponseBody>
    @Streaming @GET("accounts/{account}/positions") suspend fun positions(@Path("account") account: String, @QueryMap(encoded = true) query: Map<String, String>, @HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext): Response<ResponseBody>
    @Streaming @GET("accounts/{account}/orders/history") suspend fun history(@Path("account") account: String, @QueryMap(encoded = true) query: Map<String, String>, @HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext): Response<ResponseBody>
    @Streaming @GET("accounts/{account}/orders") suspend fun orders(@Path("account") account: String, @QueryMap(encoded = true) query: Map<String, String>, @HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext): Response<ResponseBody>
    @Streaming @GET("accounts/{account}/orders/{order}") suspend fun order(@Path("account") account: String, @Path("order") order: String, @HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext): Response<ResponseBody>
    @Streaming @GET("accounts/{account}/executions/{order}") suspend fun executions(@Path("account") account: String, @Path("order") order: String, @HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext): Response<ResponseBody>
}

/** Declaring interface is trusted Retrofit Invocation metadata; callers cannot select logging policy. */
internal interface DnseTradeEndpoints {
    @Streaming @GET("accounts") suspend fun accounts(@HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext): Response<ResponseBody>
    @Streaming @GET("accounts/{account}/balances") suspend fun balances(@Path("account") account: String, @HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext): Response<ResponseBody>
    @Streaming @GET("accounts/{account}/loan-packages") suspend fun packages(@Path("account") account: String, @QueryMap(encoded = true) query: Map<String, String>, @HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext): Response<ResponseBody>
    @Streaming @GET("accounts/{account}/ppse") suspend fun ppse(@Path("account") account: String, @QueryMap(encoded = true) query: Map<String, String>, @HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext): Response<ResponseBody>
    @Streaming @GET("accounts/{account}/orders") suspend fun orders(@Path("account") account: String, @QueryMap(encoded = true) query: Map<String, String>, @HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext): Response<ResponseBody>
    @Streaming @GET("accounts/{account}/orders/{order}") suspend fun order(@Path("account") account: String, @Path("order") order: String, @QueryMap(encoded = true) query: Map<String, String>, @HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext): Response<ResponseBody>
    @Streaming @POST("registration/send-email-otp") suspend fun emailOtp(@HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext, @Body body: RequestBody): Response<ResponseBody>
    @Streaming @POST("registration/trading-token") suspend fun token(@HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext, @Body body: RequestBody): Response<ResponseBody>
    @Streaming @POST("accounts/{account}/orders") suspend fun place(@Path("account") account: String, @QueryMap(encoded = true) query: Map<String, String>, @HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext, @Body body: RequestBody): Response<ResponseBody>
    @Streaming @DELETE("accounts/{account}/orders/{order}") suspend fun cancel(@Path("account") account: String, @Path("order") order: String, @QueryMap(encoded = true) query: Map<String, String>, @HeaderMap headers: Map<String, String>, @Tag context: DnseCallContext): Response<ResponseBody>
}
