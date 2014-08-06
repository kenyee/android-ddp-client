package com.keysolutions.ddpclient.android;

import android.util.Log;
import java.util.ArrayList;
import java.util.Map;

/**
 * Created by Beltran Berrocal on 2014/08/05.
 * Inspired by Kenyee's Party example
 * This is a generic object that represents an Meteor Collection Doc
 * one doc coming from mongoDB
 * it has all the functions needed to read all the main properties that are usually found in a mongoDB doc
 */
public class MeteorCollectionDoc {

    /**
     * This is a reference to the hashmap object in our "data store"
     * so we can look up fields dynamically
     */
    protected Map<String, Object> mFields;

    /** This is our object ID */
    protected String mDocId;

    /////////////////////////////////////// CONSTRUCTOR

    /**
     * Constructor
     * @param docId to set the id of this document
     * @param fields sets the object with all the fields of the document
     */
    public MeteorCollectionDoc (String docId, Map<String, Object> fields) {
        Log.v("MeteorCollectionDoc", "Constructor - " + "docId = [" + docId + "], fields = [" + fields + "]");
        this.mFields = fields;
        this.mDocId = docId;
    }



    /////////////////////////////////////// GETTERS & SETTERS

    /**
     * Gets Meteor object ID
     * @return object ID string
     */
    public String getId() {
        return mDocId;
    }

    /**
     * Generic Field getter
     * gets any generic field - lightly wrapper of the .get method of a Map
     *
     * PROBLEM:
     * Java doesn't yet provide a way to represent and return generic types,
     * so you can't have a function getField without specifying if the return is an Int, a String or anything else
     * there are work arounds like using a generic type T - but it's overkill for this
     * https://google-gson.googlecode.com/svn/trunk/gson/docs/javadocs/com/google/gson/reflect/TypeToken.html
     * http://google-gson.googlecode.com/svn/tags/1.1.1/docs/javadocs/com/google/gson/reflect/TypeToken.html
     * https://code.google.com/p/guava-libraries/wiki/ReflectionExplained
     *
     * test made that failed
     * public Class<?> getField (String keyName)    // Wildcard - Fails - Error:(54, 27) incompatible types - found   : java.lang.Object - required: java.lang.Class<?>
     *
     * SOLUTION: by returning a generic Object it requires you to Cast the type to whatever you want
     * example
     * String    stringField = (String)    MeteorCollectionDocSubclass.getField("stringFieldName");
     * Integer   intField    = (Integer)   MeteorCollectionDocSubclass.getField("nameOfIntField")
     * Boolean   boolField   = (Boolean)   MeteorCollectionDocSubclass.getField("isImportant");
     * ArrayList arrayField  = (ArrayList) MeteorCollectionDocSubclass.getField("arrayName");
     * to extract an array of objects arr: [{...}, {...}] use the getArrayOfObjects
     *
     * @param keyName String representation of the object's field name you want to read
     *        ie obj = { fooField: "value of foo field}
     *        String foo = (String) MeteorCollectionDocSubclass.getField("fooField");
     * @return Object Generic object that you need to cast to whatever you want
     */
    public Object getField (String keyName) {
        //Log.v("MeteorCollectionDoc", "getField - " + "KeyName = [ " + keyName + " ]");
        return mFields.get(keyName);
    }


    /**
     * get an array of objects whose key is the value of the keyName param
     * ie: keyName = messages  retrieves an { messages: [ {...}, {...}]}
     * usage: ddp.getDocument(docId).getArrayOfObjects("messages")
     * or inside a subclass
     * ArrayList msgArr = this.getArrayOfObjects("messages");
     * @param KeyName string value of the key
     * @return array of messages
     */
    //@SuppressWarnings("unchecked")
    public ArrayList<Map<String, String>> getArrayOfObjects(String KeyName) {
         Object arr = mFields.get(KeyName);
        //Log.v("MeteorCollectionDoc", "getArrayOfObjects - " + "KeyName = [ " + KeyName + " ]");
        return ((ArrayList<Map<String, String>>) arr);
    }


}
