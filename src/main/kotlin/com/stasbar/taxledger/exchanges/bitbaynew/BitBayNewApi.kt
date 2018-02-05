/*
 * Copyright (c) 2018 Stanislaw stasbar Baranski
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 *          __             __
 *    _____/ /_____ ______/ /_  ____ ______
 *   / ___/ __/ __ `/ ___/ __ \/ __ `/ ___/
 *  (__  ) /_/ /_/ (__  ) /_/ / /_/ / /
 * /____/\__/\__,_/____/_.___/\__,_/_/
 *            taxledger@stasbar.com
 */

package com.stasbar.taxledger.exchanges.bitbaynew

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.stasbar.taxledger.Constants
import com.stasbar.taxledger.DEBUG
import com.stasbar.taxledger.ExchangeApi
import com.stasbar.taxledger.Logger
import com.stasbar.taxledger.exchanges.bitbaynew.models.BitBayNewTransactions
import com.stasbar.taxledger.models.Credential
import com.stasbar.taxledger.models.Transaction
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import java.net.URLEncoder

interface BitbayService {
    /**
     * history of account transactions
     *
     * @param query - zapytanie zawierające parametry filtorwania przesłane jako obiekt JSON z następującymi polami:
     * markets - Tablica marketów
     * rateFrom - Minimalna wartość kursu
     * rateTo - Maksymalna wartość kursu
     * userAction - Typ transakcji (Sell / Buy)
     */
    @GET("trading/history/transactions")
    fun transactions(@retrofit2.http.Query("query", encoded = true) query: String?): Call<JsonElement>
}


class BitBayError(val errorsJsonArray: JsonArray) : Exception() {
    val errorToDescription = mapOf(
            Pair("PERMISSIONS_NOT_SUFFICIENT", "Uprawnienia nadane kluczowi API nie są wystarczające do wykonania akcji"),
            Pair("INVALID_HASH_SIGNATURE", "Wygenerowany podpis zapytania (API-Hash) jest niepoprawny"),
            Pair("ACTION_BLOCKED", "Akcja jest zablokowana na koncie użytkownika"),
            Pair("ACTION_LIMIT_EXCEEDED", "Limit wywołań akcji został wykorzystany, należy odczekać kilka minut przed kolejnym zapytaniem"),
            Pair("USER_OFFER_COUNT_LIMIT_EXCEEDED", "Limit ofert wystawionych do marketu dla danego rynku został wyczerpany"),
            Pair("MALFORMED_REQUEST", "JSON przesłany w zapytaniu jest uszkodzony"),
            Pair("INVALID_REQUEST", "Zapytanie zostało skonstruowane nieprawidłowo"),
            Pair("MARKET_CODE_CANNOT_BE_EMPTY", "Nie podano kodu marketu")
    )

    override val message: String?
        get() = toString()

    override fun toString(): String {
        val stringBuilder = StringBuilder()
        errorsJsonArray.forEach { stringBuilder.appendln("${it.asString} : ${errorToDescription[it.asString]}") }
        return stringBuilder.toString()

    }

}

class BitBayNewApi(credentials: LinkedHashSet<Credential>, private val gson: Gson) : ExchangeApi {
    private val publicKey: String = credentials.first { it.name == "publicKey" }.value
    private val privateKey: String = credentials.first { it.name == "privateKey" }.value
    private val URI = "https://api.bitbay.net/rest/"

    private val service: Lazy<BitbayService> = lazy {
        val logInterceptor = HttpLoggingInterceptor()
        logInterceptor.level = if (DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE

        val httpClient = OkHttpClient.Builder()
                .addNetworkInterceptor(BitBayNewHeaderInterceptor(publicKey, privateKey))
                .addNetworkInterceptor(logInterceptor)
                .build()

        val gson = GsonBuilder().setDateFormat(Constants.DATE_FORMAT).create()
        val retrofit = Retrofit.Builder()
                .client(httpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .baseUrl(URI)
                .build()
        retrofit.create(BitbayService::class.java)
    }

    override fun transactions(): List<Transaction> {
        val query = "{\n" +
                "  \"limit\":\"1000000000\",\n" +
                "  \"fromTime\": null,\n" +
                "  \"toTime\" : null,\n" +
                "  \"markets\" : []\n" +
                "}"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val response = service.value.transactions(encodedQuery).execute()

        return if (response.isSuccessful) {
            val responseBody = response.body()
            try {
                val transactionsResponse: BitBayNewTransactions? = gson.fromJson(responseBody, object : TypeToken<BitBayNewTransactions>() {}.type)

                transactionsResponse?.let {
                    if (it.status == "Fail")
                        throw BitBayError(responseBody?.asJsonObject?.getAsJsonArray("errors")!!)

                    return transactionsResponse.items.map { it.toTransaction() }
                }
                return emptyList()
            } catch (e: JsonSyntaxException) {
                Logger.err(e.message)
                Logger.err(responseBody.toString())
                emptyList<Transaction>()
            } catch (e: BitBayError) {
                e.printStackTrace()
                Logger.err(e.message)
                emptyList<Transaction>()
            }
        } else {
            Logger.err("Unsuccessfully fetched transactions error code: ${response.code()} body: ${response.errorBody()?.charStream()?.readText()} ")
            emptyList()
        }


    }


}

