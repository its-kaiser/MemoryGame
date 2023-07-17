package com.example.mymemory

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mymemory.models.BoardSize
import com.example.mymemory.utils.BitmapScaler
import com.example.mymemory.utils.EXTRA_BOARD_SIZE
import com.example.mymemory.utils.EXTRA_GAME_NAME
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import java.io.ByteArrayOutputStream

class CreateActivity : AppCompatActivity() {

    companion object{
        private const val PICK_PHOTO_CODE=655
        private const val TAG="CreateActivity"
        private const val READ_EXTERNAL_PHOTOS_CODE=248
        private const val READ_PHOTOS_PERMISSION = android.Manifest.permission.READ_EXTERNAL_STORAGE
        private const val MIN_GAME_LENGTH=4
        private const val MAX_GAME_LENGTH=14
    }
    private lateinit var boardSize: BoardSize
    private var numImagesRequired=-1

    private lateinit var adapter:ImagePickerAdapter
    private lateinit var rvImagePicker:RecyclerView
    private lateinit var etGameName:EditText
    private lateinit var btnSave:Button
    private lateinit var pbUploading:ProgressBar
    private var chosenImageUris = mutableListOf<Uri>()
    private val storage = Firebase.storage
    private val db = Firebase.firestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create)

        rvImagePicker=findViewById(R.id.rvImagePicker)
        etGameName=findViewById(R.id.etGameName)
        btnSave=findViewById(R.id.btnSave)
        pbUploading=findViewById(R.id.pbUploading)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        boardSize=intent.getSerializableExtra(EXTRA_BOARD_SIZE) as BoardSize
        numImagesRequired=boardSize.getNumPairs()
        supportActionBar?.title="Choose pics (0/$numImagesRequired)"

        //when the save button is clicked
        btnSave.setOnClickListener{
            saveDataToFirebase()
        }

        //setting the maximum length of the edittext
        etGameName.filters= arrayOf(InputFilter.LengthFilter(MAX_GAME_LENGTH))

        //saveButton enabled or not corresponding to the changes in edittext
        etGameName.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                btnSave.isEnabled=shouldEnabledSaveButton()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

         adapter=ImagePickerAdapter(this,chosenImageUris,boardSize,object :ImagePickerAdapter.ImageClickListener{
            override fun onPlaceHolderClicked() {
                /*if(isPermissionGranted(this@CreateActivity, READ_PHOTOS_PERMISSION)) {
                    launchIntentForPhotos()
                }
                else{
                    requestPermission(this@CreateActivity, READ_PHOTOS_PERMISSION,
                        READ_EXTERNAL_PHOTOS_CODE)
                }*/
                launchIntentForPhotos()
            }

        })
        rvImagePicker.adapter=adapter
        rvImagePicker.setHasFixedSize(true)
        rvImagePicker.layoutManager=GridLayoutManager(this,boardSize.getWidth())
    }

    /* override fun onRequestPermissionsResult(
         requestCode: Int,
         permissions: Array<out String>,
         grantResults: IntArray
     ) {
         if(requestCode== READ_EXTERNAL_PHOTOS_CODE){
             if(grantResults.isNotEmpty() && grantResults[0]==PackageManager.PERMISSION_GRANTED) {
                 launchIntentForPhotos()
             }
             else{
                 Toast.makeText(
                     this,
                     "In order to make a custom game, you need to provide access to your photos",
                     Toast.LENGTH_LONG
                   ).show()
             }
         }
         super.onRequestPermissionsResult(requestCode, permissions, grantResults)
     }*/

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if(item.itemId==android.R.id.home){
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    //data retrieved from the intent is overridden here
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode!= PICK_PHOTO_CODE || resultCode!= Activity.RESULT_OK || data==null){
            Log.w(TAG,"Didn't get data from the launched activity, user likely cancelled flow")
            return
        }
        val selectedUri = data.data // if only pic was picked
        val clipData = data.clipData//if a list of pics was picked

        if(clipData!=null){
            Log.i(TAG,"ClipData numImages ${clipData.itemCount}: $clipData")

            for(i in 0 until clipData.itemCount){
                val clipItem=clipData.getItemAt(i)
                if(chosenImageUris.size<numImagesRequired){
                    chosenImageUris.add(clipItem.uri)
                }
            }
        }else if(selectedUri!=null){
            Log.i(TAG,"data: $selectedUri")
            chosenImageUris.add(selectedUri)
        }
        adapter.notifyDataSetChanged()
        supportActionBar?.title="Choose pics (${chosenImageUris.size}/${numImagesRequired })"
        btnSave.isEnabled=shouldEnabledSaveButton()
    }

    private fun shouldEnabledSaveButton(): Boolean {
        if(chosenImageUris.size!=numImagesRequired){
            return false
        }
        if(etGameName.text.isBlank() || etGameName.text.length< MIN_GAME_LENGTH){
            return false
        }
        return true
    }

    private fun launchIntentForPhotos() {
        val intent= Intent(Intent.ACTION_PICK)
        intent.type="image/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
        startActivityForResult(Intent.createChooser(intent,"Chose pics"),PICK_PHOTO_CODE)
    }

    private fun saveDataToFirebase() {
        Log.i(TAG,"Save data to firebase")

        //disabling the button after creating it once so that user doesn't spam the button
        btnSave.isEnabled=false
        val customGameName= etGameName.text.toString()
        //check that we are not over writing someone else's data
        db.collection("games").document(customGameName).get().addOnSuccessListener { document->
            if(document!=null && document.data!=null){
                AlertDialog.Builder(this)
                    .setTitle("Name Taken")
                    .setMessage("A game already exists with the game name '$customGameName'. Please choose another")
                    .setPositiveButton("OK",null)
                    .show()
                //if the user encounters a failure they can try again hence enabling the button
                btnSave.isEnabled=true
            }
            else{
                handleImageUploading(customGameName)
            }
        }.addOnFailureListener(){ exception->
            Log.e(TAG,"Encountered error while saving memory game",exception)
            Toast.makeText(this,"Encountered error while saving memory game",Toast.LENGTH_SHORT).show()
            btnSave.isEnabled=true
        }
    }

    private fun handleImageUploading(gameName: String) {
        pbUploading.visibility= View.VISIBLE
        var didEncounterError=false
        val uploadedImageUrls = mutableListOf<String>()
        for((index,photoUri) in chosenImageUris.withIndex()){
            val imageByteArray =getImageByteArray(photoUri)
            //filepath where the image should live in the fire storage
            val filePath = "images/$gameName/${System.currentTimeMillis()}-${index}.jpg"

            //reference to the location where we gonna save this photo
            val photoReference = storage.reference.child(filePath)

            //uploading the bytes to firebase storage
            photoReference.putBytes(imageByteArray)
                .continueWithTask{ photoUploadTask->
                    Log.i(TAG,"Uploaded bytes: ${photoUploadTask.result?.bytesTransferred}")
                    photoReference.downloadUrl
                }.addOnCompleteListener{downloadUrlTask->
                    //image upload was not a success
                    if(!downloadUrlTask.isSuccessful){
                        Log.e(TAG,"Exception with Firebase Storage",downloadUrlTask.exception)
                        Toast.makeText(this,"Failed to upload image",Toast.LENGTH_SHORT).show()
                        didEncounterError=true
                        return@addOnCompleteListener
                    }
                    if(didEncounterError){
                        pbUploading.visibility=View.GONE
                        return@addOnCompleteListener
                    }

                    //image upload was a success
                    val downloadUrl= downloadUrlTask.result.toString()
                    uploadedImageUrls.add(downloadUrl)

                    //updating the progress in progress bar
                    pbUploading.progress=uploadedImageUrls.size*100/chosenImageUris.size
                    Log.i(TAG,"Finished uploading $photoUri, num uploaded ${uploadedImageUrls.size}")

                    //checking if all the images have been uploaded
                    if(uploadedImageUrls.size==chosenImageUris.size){
                        handleAllImagesUploaded(gameName,uploadedImageUrls) //success callback that all images have been uploaded
                    }
                }
        }
    }

    private fun handleAllImagesUploaded(gameName: String, imageUrls: MutableList<String>) {
        //TODO: upload this info to Firestore
        db.collection("games").document(gameName)
            .set(mapOf("images" to imageUrls))
            .addOnCompleteListener{ gameCreationTask->
                pbUploading.visibility=View.GONE
                if(!gameCreationTask.isSuccessful){
                    Log.e(TAG,"Exception with game creation",gameCreationTask.exception)
                    Toast.makeText(this,"Failed game creation",Toast.LENGTH_SHORT).show()
                    return@addOnCompleteListener
                }
                Log.i(TAG,"Successfully created game $gameName")

                //alerting the user that they have now created the game and should navigate back
                //to main activity to play the game
                AlertDialog.Builder(this)
                    .setTitle("Upload Complete!! Let's play your game '$gameName'")
                    .setPositiveButton("Ok"){_,_->
                        val resultData=Intent()
                        resultData.putExtra(EXTRA_GAME_NAME,gameName)
                        setResult(Activity.RESULT_OK, resultData)
                        finish()
                    }.show()
            }
    }


    //downscaling the resolution of the image
    private fun getImageByteArray(photoUri: Uri): ByteArray{
        //getting the original bitmap  -> here P=Pie
        val originalBitmap = if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.P){
            val source = ImageDecoder.createSource(contentResolver,photoUri)
            ImageDecoder.decodeBitmap(source)
        }else{
            MediaStore.Images.Media.getBitmap(contentResolver,photoUri)
        }
        Log.i(TAG,"Original width ${originalBitmap.width} and height ${originalBitmap.height}")

        val scaledBitmap= BitmapScaler.scaleToFitHeight(originalBitmap,250)

        Log.i(TAG,"Scaled width ${scaledBitmap.width} and height ${scaledBitmap.height}")
        val byteOutputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG,60,byteOutputStream)

        return byteOutputStream.toByteArray()
    }
}