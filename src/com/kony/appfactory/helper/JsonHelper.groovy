package com.kony.appfactory.helper

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * This class will help in Mapping JSON with Groovy POJO based on FieldNamingPolicy.
 * If field naming Policy is not defined currently a new method can be added mating
 * FieldNamingPolicy.
 */
class JsonHelper {
    /**
     * This method helps in creating Groovy POJO mapping from Json content
     * @param json - This is JSON content in string format
     * @param klass - target POJO class type
     * @return POJO class object with mapped fields
     */
    protected static <T> T parseJson(String json, Class<T> klass) {
        Gson gson = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        return gson.fromJson(json, klass)
    }
}
