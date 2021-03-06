package org.torusresearch.torusutils;

import com.google.gson.Gson;
import org.torusresearch.torusutils.apis.*;
import org.torusresearch.torusutils.helpers.*;
import org.torusresearch.torusutils.types.*;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class TorusUtils {

    private static BigInteger secp256k1N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

    private TorusUtils() {
    }


    private static CompletableFuture<RetrieveSharesResponse> retrieveShares(String[] endpoints, BigInteger[] indexes, String verifier, HashMap<String, Object> verifierParams, String idToken) throws InvalidAlgorithmParameterException, NoSuchAlgorithmException, NoSuchProviderException {
        List<CompletableFuture<String>> promiseArr = new ArrayList<>();
        // generate temporary private and public key that is used to secure receive shares
        ECKeyPair tmpKey = Keys.createEcKeyPair();
        String pubKey = tmpKey.getPublicKey().toString(16);
        String pubKeyX = pubKey.substring(0, pubKey.length() / 2);
        String pubKeyY = pubKey.substring(pubKey.length() / 2);
        String tokenCommitment = org.web3j.crypto.Hash.sha3String(idToken);
        int t = Math.floorDiv(endpoints.length, 4);
        int k = t * 2 + 1;

        // make commitment requests to endpoints
        Instant instant = Instant.now();
        for (int i = 0; i < endpoints.length; i++) {
            CompletableFuture<String> p = APIUtils.post(endpoints[i], APIUtils.generateJsonRPCObject("CommitmentRequest", new CommitmentRequestParams("mug00", tokenCommitment.substring(2), pubKeyX, pubKeyY, String.valueOf(instant.toEpochMilli()), verifier)));
            promiseArr.add(i, p);
        }
        // send share request once k + t number of commitment requests have completed
        return new Some<>(promiseArr, resultArr -> {

            List<String> resultArrList = Arrays.asList(resultArr);
            List<String> completedRequests = resultArrList.stream().filter(resp -> resp != null && !resp.equals("")).collect(Collectors.toList());
            CompletableFuture<List<String>> completableFuture = new CompletableFuture<>();
            if (completedRequests.size() >= k + t) {
                completableFuture.complete(completedRequests);
                return completableFuture;
            } else {
                throw new PredicateFailedException("insufficient responses");
            }
        })
                .getCompletableFuture()
                .thenComposeAsync(responses -> {
                    List<CompletableFuture<String>> promiseArrRequests = new ArrayList<>();
                    List<String> nodeSigs = new ArrayList<>();
                    for (String respons : responses) {
                        if (respons != null && !respons.equals("")) {
                            Gson gson = new Gson();
                            JsonRPCResponse nodeSigResponse = gson.fromJson(respons, JsonRPCResponse.class);
                            if (nodeSigResponse != null && nodeSigResponse.getResult() != null) {
                                nodeSigs.add(gson.toJson(nodeSigResponse.getResult()));
                            }
                        }
                    }
                    NodeSignature[] nodeSignatures = new NodeSignature[nodeSigs.size()];
                    for (int l = 0; l < nodeSigs.size(); l++) {
                        Gson gson = new Gson();
                        nodeSignatures[l] = gson.fromJson(nodeSigs.get(l), NodeSignature.class);
                    }
                    ShareRequestItem[] shareRequestItems = {new ShareRequestItem((String) verifierParams.get("verifier_id"), idToken, nodeSignatures, verifier)};
                    for (String endpoint : endpoints) {
                        String req = APIUtils.generateJsonRPCObject("ShareRequest", new ShareRequestParams(shareRequestItems));
                        promiseArrRequests.add(APIUtils.post(endpoint, req));
                    }
                    return new Some<>(promiseArrRequests, shareResponses -> {
                        // check if threshold number of nodes have returned the same user public key
                        BigInteger privateKey = null;
                        String ethAddress = null;
                        List<String> completedResponses = new ArrayList<>();
                        for (String shareResponse : shareResponses) {
                            if (shareResponse != null && !shareResponse.equals("")) {
                                Gson gson = new Gson();
                                JsonRPCResponse shareResponseJson = gson.fromJson(shareResponse, JsonRPCResponse.class);
                                if (shareResponseJson != null && shareResponseJson.getResult() != null) {
                                    completedResponses.add(gson.toJson(shareResponseJson.getResult()));
                                }
                            }
                        }
                        List<String> completedResponsesPubKeys = completedResponses.stream().map(x -> {
                            Gson gson = new Gson();
                            KeyAssignResult keyAssignResult = gson.fromJson(x, KeyAssignResult.class);
                            if (keyAssignResult == null || keyAssignResult.getKeys() == null || keyAssignResult.getKeys().length == 0) {
                                return null;
                            }
                            KeyAssignment keyAssignResultFirstKey = keyAssignResult.getKeys()[0];
                            return gson.toJson(keyAssignResultFirstKey.getPublicKey());
                        }).collect(Collectors.toList());

                        String thresholdPublicKeyString = Utils.thresholdSame(completedResponsesPubKeys, k);
                        Gson gson = new Gson();
                        PubKey thresholdPubKey = null;
                        if (thresholdPublicKeyString != null && !thresholdPublicKeyString.equals("")) {
                            thresholdPubKey = gson.fromJson(thresholdPublicKeyString, PubKey.class);
                        }
                        if (completedResponses.size() >= k && thresholdPubKey != null) {
                            List<DecryptedShare> decryptedShares = new ArrayList<>();
                            for (int i = 0; i < shareResponses.length; i++) {
                                if (shareResponses[i] != null && !shareResponses[i].equals("")) {
                                    JsonRPCResponse currentJsonRPCResponse = gson.fromJson(shareResponses[i], JsonRPCResponse.class);
                                    if (currentJsonRPCResponse != null && currentJsonRPCResponse.getResult() != null && !currentJsonRPCResponse.getResult().equals("")) {
                                        KeyAssignResult currentShareResponse = gson.fromJson(gson.toJson(currentJsonRPCResponse.getResult()), KeyAssignResult.class);
                                        if (currentShareResponse != null && currentShareResponse.getKeys() != null && currentShareResponse.getKeys().length > 0) {
                                            KeyAssignment firstKey = currentShareResponse.getKeys()[0];
                                            if (firstKey.getMetadata() != null) {
                                                try {
                                                    AES256CBC aes256cbc = new AES256CBC(tmpKey.getPrivateKey().toString(16), firstKey.getMetadata().getEphemPublicKey(), firstKey.getMetadata().getIv());
                                                    // Implementation specific oddity - hex string actually gets passed as a base64 string
                                                    String hexUTF8AsBase64 = firstKey.getShare();
                                                    String hexUTF8 = new String(Base64.decode(hexUTF8AsBase64), StandardCharsets.UTF_8);
                                                    byte[] encryptedShareBytes = AES256CBC.toByteArray(new BigInteger(hexUTF8, 16));
                                                    BigInteger share = new BigInteger(1, aes256cbc.decrypt(Base64.encodeBytes(encryptedShareBytes)));
                                                    decryptedShares.add(new DecryptedShare(indexes[i], share));
                                                } catch (Exception e) {
                                                    e.printStackTrace();
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            List<List<Integer>> allCombis = Utils.kCombinations(decryptedShares.size(), k);
                            for (List<Integer> currentCombi : allCombis) {
                                List<DecryptedShare> currentCombiShares = IntStream.range(0, decryptedShares.size()).filter(x -> currentCombi.contains(x)).mapToObj(decryptedShares::get).collect(Collectors.toList());
                                BigInteger derivedPrivateKey = TorusUtils.lagrangeInterpolation(currentCombiShares.stream().map(DecryptedShare::getValue).toArray(BigInteger[]::new), currentCombiShares.stream().map(DecryptedShare::getIndex).toArray(BigInteger[]::new));
                                assert derivedPrivateKey != null;
                                ECKeyPair derivedECKeyPair = ECKeyPair.create(derivedPrivateKey);
                                String derivedPubKeyString = derivedECKeyPair.getPublicKey().toString(16);
                                String derivedPubKeyX = derivedPubKeyString.substring(0, derivedPubKeyString.length() / 2);
                                String derivedPubKeyY = derivedPubKeyString.substring(derivedPubKeyString.length() / 2);
                                if (new BigInteger(derivedPubKeyX, 16).compareTo(new BigInteger(thresholdPubKey.getX(), 16)) == 0 &&
                                        new BigInteger(derivedPubKeyY, 16).compareTo(new BigInteger(thresholdPubKey.getY(), 16)) == 0
                                ) {
                                    privateKey = derivedPrivateKey;
                                    ethAddress = "0x" + Hash.sha3(derivedECKeyPair.getPublicKey().toString(16)).substring(64 - 38);
                                    break;
                                }
                            }
                            if (privateKey == null) {
                                throw new PredicateFailedException("could not derive private key");
                            }
                        } else {
                            throw new PredicateFailedException("could not get enough shares");
                        }

                        return CompletableFuture.completedFuture(new RetrieveSharesResponse(ethAddress, privateKey.toString(16)));
                    }).getCompletableFuture();
                });
    }


//    public static void main(String[] args) {
//        String[] endpoints = {"https://lrc-test-13-a.torusnode.com/jrpc", "https://lrc-test-13-b.torusnffode.com/jrpc", "https://lrc-test-13-c.torusnode.com/jrpc", "https://lrc-test-13-d.torusnode.com/jrpc", "https://lrc-test-13-e.torusnode.com/jrpc"};
//        TorusNodePub[] nodePubKeys = {
//                new TorusNodePub("4086d123bd8b370db29e84604cd54fa9f1aeb544dba1cc9ff7c856f41b5bf269", "fde2ac475d8d2796aab2dea7426bc57571c26acad4f141463c036c9df3a8b8e8"),
//                new TorusNodePub("1d6ae1e674fdc1849e8d6dacf193daa97c5d484251aa9f82ff740f8277ee8b7d", "43095ae6101b2e04fa187e3a3eb7fbe1de706062157f9561b1ff07fe924a9528"),
//                new TorusNodePub("fd2af691fe4289ffbcb30885737a34d8f3f1113cbf71d48968da84cab7d0c262", "c37097edc6d6323142e0f310f0c2fb33766dbe10d07693d73d5d490c1891b8dc"),
//                new TorusNodePub("e078195f5fd6f58977531135317a0f8d3af6d3b893be9762f433686f782bec58", "843f87df076c26bf5d4d66120770a0aecf0f5667d38aa1ec518383d50fa0fb88"),
//                new TorusNodePub("a127de58df2e7a612fd256c42b57bb311ce41fd5d0ab58e6426fbf82c72e742f", "388842e57a4df814daef7dceb2065543dd5727f0ee7b40d527f36f905013fa96"),
//        };
//        BigInteger[] indexes = {new BigInteger("1"), new BigInteger("2"), new BigInteger("3"), new BigInteger("4"), new BigInteger("5")};
//        HashMap<String, Object> verifierParams = new HashMap<>();
//        verifierParams.put("verifier_id", "tetratorus@gmail.com");
//        String idToken = "";
//        try {
//            RetrieveSharesResponse retrieveSharesResponse = TorusUtils.retrieveShares(endpoints, indexes, "google", verifierParams, idToken).get();
//            System.out.println(retrieveSharesResponse.getEthAddress());
//            System.out.println(retrieveSharesResponse.getPrivKey());
//        } catch (Exception e) {
//            System.out.println("FAILED");
//            e.printStackTrace();
//        }
////        try {
////            TorusPublicKey pubAddress = TorusUtils.getPublicAddress(endpoints, nodePubKeys, new VerifierArgs("google", "fffwwsss@tor.us"), true).get();
////            System.out.println(pubAddress.getAddress());
////            System.out.println(pubAddress.getX());
////            System.out.println(pubAddress.getY());
////        } catch (Exception e) {
////            e.printStackTrace();
////        }
//    }

    static BigInteger lagrangeInterpolation(BigInteger[] shares, BigInteger[] nodeIndex) {
        if (shares.length != nodeIndex.length) {
            return null;
        }
        BigInteger secret = new BigInteger("0");
        for (int i = 0; i < shares.length; i++) {
            BigInteger upper = new BigInteger("1");
            BigInteger lower = new BigInteger("1");
            for (int j = 0; j < shares.length; j++) {
                if (i != j) {
                    upper = upper.multiply(nodeIndex[j].negate());
                    upper = upper.mod(secp256k1N);
                    BigInteger temp = nodeIndex[i].subtract(nodeIndex[j]);
                    temp = temp.mod(secp256k1N);
                    lower = lower.multiply(temp).mod(secp256k1N);
                }
            }
            BigInteger delta = upper.multiply(lower.modInverse(secp256k1N)).mod(secp256k1N);
            delta = delta.multiply(shares[i]).mod(secp256k1N);
            secret = secret.add(delta);
        }
        return secret.mod(secp256k1N);
    }

    public static String generateAddressFromPrivKey(String privateKey) {
        BigInteger privKey = new BigInteger(privateKey, 16);
        return Keys.getAddress(ECKeyPair.create(privKey.toByteArray()));
    }

    static CompletableFuture<TorusPublicKey> _getPublicAddress(String[] endpoints, TorusNodePub[] torusNodePubs, VerifierArgs verifierArgs, boolean isExtended) {
        CompletableFuture<TorusPublicKey> completableFuture = new CompletableFuture<>();
        Utils.keyLookup(endpoints, verifierArgs.getVerifier(), verifierArgs.getVerifierId())
                .thenComposeAsync(keyLookupResult -> {
                    if (keyLookupResult.getErrResult() != null) {
                        return Utils
                                .keyAssign(endpoints, torusNodePubs, null, null, verifierArgs.getVerifier(), verifierArgs.getVerifierId())
                                .thenComposeAsync(k -> Utils.keyLookup(endpoints, verifierArgs.getVerifier(), verifierArgs.getVerifierId()))
                                .thenComposeAsync(res -> {
                                    if (res == null || res.getKeyResult() == null) {
                                        completableFuture.completeExceptionally(new Exception("could not get lookup, no results"));
                                        return null;
                                    }
                                    Gson gson = new Gson();
                                    VerifierLookupRequestResult verifierLookupRequestResult = gson.fromJson(res.getKeyResult(), VerifierLookupRequestResult.class);
                                    if (verifierLookupRequestResult == null || verifierLookupRequestResult.getKeys() == null || verifierLookupRequestResult.getKeys().length == 0) {
                                        completableFuture.completeExceptionally(new Exception("could not get lookup, no keys"));
                                        return null;
                                    }
                                    VerifierLookupItem verifierLookupItem = verifierLookupRequestResult.getKeys()[0];
                                    return CompletableFuture.completedFuture(verifierLookupItem);
                                });
                    }
                    if (keyLookupResult.getKeyResult() != null) {
                        Gson gson = new Gson();
                        VerifierLookupRequestResult verifierLookupRequestResult = gson.fromJson(keyLookupResult.getKeyResult(), VerifierLookupRequestResult.class);
                        if (verifierLookupRequestResult == null || verifierLookupRequestResult.getKeys() == null || verifierLookupRequestResult.getKeys().length == 0) {
                            completableFuture.completeExceptionally(new Exception("could not get lookup, no keys"));
                            return null;
                        }
                        VerifierLookupItem verifierLookupItem = verifierLookupRequestResult.getKeys()[0];
                        return CompletableFuture.completedFuture(verifierLookupItem);
                    }
                    completableFuture.completeExceptionally(new Exception("could not get lookup, no valid key result or error result"));
                    return null;
                }).thenComposeAsync(verifierLookupItem -> {
            if (verifierLookupItem == null) {
                completableFuture.completeExceptionally(new Exception("node results do not match"));
                return null;
            }
            if (!isExtended) {
                completableFuture.complete(new TorusPublicKey(verifierLookupItem.getAddress()));
            } else {
                completableFuture.complete(new TorusPublicKey(verifierLookupItem.getPub_key_X(), verifierLookupItem.getPub_key_Y(), verifierLookupItem.getAddress()));
            }
            return null;
        }).exceptionally(completableFuture::completeExceptionally);
        return completableFuture;
    }

    public static CompletableFuture<TorusPublicKey> getPublicAddress(String[] endpoints, TorusNodePub[] torusNodePubs, VerifierArgs verifierArgs, boolean isExtended) {
        return _getPublicAddress(endpoints, torusNodePubs, verifierArgs, isExtended);
    }

    public static CompletableFuture<TorusPublicKey> getPublicAddress(String[] endpoints, TorusNodePub[] torusNodePubs, VerifierArgs verifierArgs) {
        return _getPublicAddress(endpoints, torusNodePubs, verifierArgs, false);
    }
}
