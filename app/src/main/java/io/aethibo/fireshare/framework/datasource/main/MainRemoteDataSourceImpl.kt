/*
 * Created by Karic Kenan on 2.2.2021
 * Copyright (c) 2021 . All rights reserved.
 */

package io.aethibo.fireshare.framework.datasource.main

import android.net.Uri
import com.google.firebase.firestore.Query
import io.aethibo.fireshare.data.remote.main.MainRemoteDataSource
import io.aethibo.fireshare.domain.Comment
import io.aethibo.fireshare.domain.Post
import io.aethibo.fireshare.domain.PostToUpdateBody
import io.aethibo.fireshare.domain.User
import io.aethibo.fireshare.domain.request.PostRequestBody
import io.aethibo.fireshare.domain.request.ProfileUpdateRequestBody
import io.aethibo.fireshare.framework.utils.AppConst
import io.aethibo.fireshare.framework.utils.FirebaseUtil.auth
import io.aethibo.fireshare.framework.utils.FirebaseUtil.firestore
import io.aethibo.fireshare.framework.utils.FirebaseUtil.storage
import io.aethibo.fireshare.framework.utils.Resource
import io.aethibo.fireshare.framework.utils.safeCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.*

class MainRemoteDataSourceImpl : MainRemoteDataSource {

    private val posts = firestore.collection(AppConst.postsCollection)
    private val users = firestore.collection(AppConst.usersCollection)
    private val comments = firestore.collection(AppConst.commentsCollection)

    override suspend fun getPostsForProfile(uid: String) = withContext(Dispatchers.IO) {
        safeCall {
            val profilePosts = posts.whereEqualTo("ownerId", uid)
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .get()
                    .await()
                    .toObjects(Post::class.java)
                    .onEach { post ->
                        val user: User = getSingleUser(post.ownerId).data!!
                        post.authorProfilePictureUrl = user.photoUrl
                        post.authorUsername = user.username
                    }

            Resource.Success(profilePosts)
        }
    }

    override suspend fun createPost(body: PostRequestBody): Resource<Any> = withContext(Dispatchers.IO) {
        safeCall {
            val uid = auth.uid!!
            val postId = UUID.randomUUID().toString()
            val imageUploadResult = storage.getReference(postId).putFile(body.imageUri).await()
            val imageUrl = imageUploadResult?.metadata?.reference?.downloadUrl?.await().toString()

            val post = Post(
                    id = postId,
                    ownerId = uid,
                    caption = body.caption,
                    imageUrl = imageUrl,
                    timestamp = System.currentTimeMillis()
            )

            posts.document(uid).collection(AppConst.usersPostsCollection).document(postId).set(post).await()

            Resource.Success(Any())
        }
    }

    override suspend fun updatePost(body: PostToUpdateBody): Resource<Any> = withContext(Dispatchers.IO) {
        safeCall {
            val uid = auth.uid!!
            val map = mutableMapOf("caption" to body.caption)

            posts
                    .document(uid)
                    .collection(AppConst.usersPostsCollection)
                    .document(body.postIdToUpdate)
                    .update(map.toMap())
                    .await()

            Resource.Success(Any())
        }
    }

    override suspend fun deletePost(post: Post): Resource<Post> = withContext(Dispatchers.IO) {
        safeCall {
            val uid = auth.uid!!
            posts.document(uid).collection(AppConst.usersPostsCollection).document(post.id).delete().await()
            storage.getReferenceFromUrl(post.imageUrl).delete().await()
            Resource.Success(post)
        }
    }

    override suspend fun toggleLikeForPost(post: Post): Resource<Boolean> = withContext(Dispatchers.IO) {
        safeCall {

            var isLiked = false

            firestore.runTransaction { transaction ->
                val uid = auth.uid!!
                val postResult = transaction.get(posts.document(uid).collection(AppConst.usersPostsCollection).document(post.id))
                val currentLikes = postResult.toObject(Post::class.java)?.likedBy ?: emptyList()

                transaction.update(
                        posts.document(uid).collection(AppConst.usersPostsCollection).document(post.id), "likedBy",
                        if (uid in currentLikes)
                            currentLikes - uid
                        else {
                            isLiked = true
                            currentLikes + uid
                        })
            }.await()

            Resource.Success(isLiked)
        }
    }

    override suspend fun getSingleUser(uid: String): Resource<User> = withContext(Dispatchers.IO) {
        safeCall {
            val user = users.document(uid).get().await().toObject(User::class.java)
                    ?: throw IllegalStateException()

            Resource.Success(user)
        }
    }

    override suspend fun updateUserProfile(body: ProfileUpdateRequestBody): Resource<Any> = withContext(Dispatchers.IO) {
        safeCall {

            val imageUrl = body.photoUrl?.let { uri -> updateProfilePicture(body.uidToUpdate, uri).toString() }

            val map = mutableMapOf("username" to body.username, "bio" to body.bio)

            imageUrl?.let { uri -> map["photoUrl"] = uri }

            users.document(body.uidToUpdate).update(map.toMap()).await()

            Resource.Success(Any())
        }
    }

    override suspend fun updateProfilePicture(uid: String, imageUri: Uri): Uri? = withContext(Dispatchers.IO) {

        val storageRef = storage.getReference(uid)
        val user = getSingleUser(uid).data as User

        if (user.photoUrl != AppConst.DEFAULT_PROFILE_PICTURE_URL)
            storage.getReferenceFromUrl(user.photoUrl).delete().await()

        storageRef.putFile(imageUri)
                .await()
                .metadata
                ?.reference
                ?.downloadUrl
                ?.await()
    }

    override suspend fun getCommentsForPost(postId: String): Resource<List<Comment>> = withContext(Dispatchers.IO) {
        safeCall {

            val commentsForPost = comments
                    .document(postId)
                    .collection(AppConst.postCommentsCollection)
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .get()
                    .await()
                    .toObjects(Comment::class.java)
                    .onEach { comment ->
                        val user = getSingleUser(comment.userId).data!!

                        comment.authorUsername = user.username
                        comment.authorProfilePictureUrl = user.photoUrl
                    }

            Resource.Success(commentsForPost)
        }
    }

    override suspend fun createComment(postId: String, commentText: String): Resource<Comment> = withContext(Dispatchers.IO) {
        safeCall {

            val uid = auth.uid!!
            val user = getSingleUser(uid).data!!
            val commentId = UUID.randomUUID().toString()

            val comment = Comment(
                    id = commentId,
                    userId = uid,
                    postId = postId,
                    comment = commentText,
                    authorUsername = user.username,
                    authorProfilePictureUrl = user.photoUrl)

            comments.document(postId).collection(AppConst.postCommentsCollection).document(commentId).set(comment).await()

            Resource.Success(comment)
        }
    }

    override suspend fun deleteComment(comment: Comment): Resource<Comment> = withContext(Dispatchers.IO) {
        safeCall {

            comments
                    .document(comment.postId)
                    .collection(AppConst.postCommentsCollection)
                    .document(comment.id)
                    .delete()
                    .await()

            Resource.Success(comment)
        }
    }
}