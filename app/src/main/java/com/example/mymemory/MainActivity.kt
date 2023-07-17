package com.example.mymemory

import android.animation.ArgbEvaluator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mymemory.models.BoardSize
import com.example.mymemory.models.MemoryGame
import com.example.mymemory.models.UserImageList
import com.example.mymemory.utils.EXTRA_BOARD_SIZE
import com.example.mymemory.utils.EXTRA_GAME_NAME
import com.github.jinatonic.confetti.CommonConfetti
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.squareup.picasso.Picasso

class MainActivity : AppCompatActivity() {

    companion object{
        private const val TAG= "MainActivity"
        private const val CREATE_REQUEST_CODE=248
    }
    private lateinit var clRoot:CoordinatorLayout
    private lateinit var memoryGame:MemoryGame
    private lateinit var rvBoard: RecyclerView
    private lateinit var tvNumMoves:TextView
    private lateinit var tvNumPairs:TextView
    private lateinit var adapter:MemoryBoardAdapter

    private val db=Firebase.firestore
    private var gameName :String? = null
    private var customGameImages: List<String>? =null
    private var boardSize:BoardSize=BoardSize.EASY
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        clRoot=findViewById(R.id.clRoot)
        rvBoard=findViewById(R.id.rvBoard)
        tvNumMoves=findViewById(R.id.tvNumMoves)
        tvNumPairs=findViewById(R.id.tvNumPairs)

        setupBoard()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main,menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId){
            R.id.mi_refresh->{
                //setup the game again
                if(memoryGame.getNumMoves()>0 && !memoryGame.haveWonGame()) {
                    showAlertDialog("Quit your current game?",null,View.OnClickListener {
                        setupBoard() })
                }else {
                    setupBoard()
                }
                return true
            }
            R.id.mi_new_size->{
                showNewSizeDialog()
                return true
            }
            R.id.mi_custom->{
                showCreationDialog()
                return true
            }
            R.id.mi_download->{
                showDownloadDialog()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if(requestCode== CREATE_REQUEST_CODE && resultCode== Activity.RESULT_OK){
            //Retrieving the game name
            val customGameName = data?.getStringExtra(EXTRA_GAME_NAME)

            if(customGameName==null){
                Log.e(TAG,"Got null custom game from Create Activity")
                return
            }
            downloadGame(customGameName)
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    //query fireStore, retrieve corresponding set of imageUrls
    private fun downloadGame(customGameName: String) {
        //document in the lambda is the list of imageUrls that we will retrieve from the fireStore
        db.collection("games").document(customGameName).get().addOnSuccessListener {document->
            val userImageList= document.toObject(UserImageList::class.java)
            if(userImageList?.images==null){
                Log.e(TAG,"Invalid custom game data from Firestore")
                Snackbar.make(clRoot,"Sorry we coundn't find any such game, '$gameName'",Snackbar.LENGTH_LONG).show()
                return@addOnSuccessListener
            }
            val numCards = userImageList.images.size*2
            boardSize=BoardSize.getByValue(numCards)
            customGameImages=userImageList.images

            //prefetching the images to remove the lag in displaying image
            for(imageUrl in userImageList.images){
                Picasso.get().load(imageUrl).fetch()
            }
            Snackbar.make(clRoot,"You are now playing $customGameName'!'",Snackbar.LENGTH_LONG).show()
            gameName = customGameName
            setupBoard()
        }.addOnFailureListener { exception ->
            Log.e(TAG,"Exception while retrieving images",exception)
        }
    }

    private fun showCreationDialog() {
        val boardSizeView=LayoutInflater.from(this).inflate(R.layout.dialog_board_size,null)
        val radioGroupSize=boardSizeView.findViewById<RadioGroup>(R.id.radioGroup)

        showAlertDialog("Create your own memory board",boardSizeView,View.OnClickListener {
            //set a new value for the board size
            val desiredBoardSize=when(radioGroupSize.checkedRadioButtonId){
                R.id.rbEasy->BoardSize.EASY
                R.id.rbMedium->BoardSize.MEDIUM
                else -> BoardSize.HARD
            }
            //navigate to a new activity
            val intent=Intent(this,CreateActivity::class.java)
            intent.putExtra(EXTRA_BOARD_SIZE,desiredBoardSize)
            startActivityForResult(intent,CREATE_REQUEST_CODE)
        })
    }

    private fun showNewSizeDialog() {
        val boardSizeView=LayoutInflater.from(this).inflate(R.layout.dialog_board_size,null)
        val radioGroupSize=boardSizeView.findViewById<RadioGroup>(R.id.radioGroup)

        //the size u are in playing in would be checked initially
        when(boardSize){
            BoardSize.EASY -> radioGroupSize.check(R.id.rbEasy)
            BoardSize.MEDIUM -> radioGroupSize.check(R.id.rbMedium)
            BoardSize.HARD -> radioGroupSize.check(R.id.rbHard)
        }
        showAlertDialog("Choose new size",boardSizeView,View.OnClickListener {
            //set a value for the board size
            boardSize=when(radioGroupSize.checkedRadioButtonId){
                R.id.rbEasy->BoardSize.EASY
                R.id.rbMedium->BoardSize.MEDIUM
                else -> BoardSize.HARD
            }
            //to remove the cache memory if a new size is selected
            gameName=null
            customGameImages=null
            setupBoard()
        })
    }

    private fun showAlertDialog(title:String,view: View?,positiveClickListener:View.OnClickListener) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Ok"){_,_->
                positiveClickListener.onClick(null)
            }.show()
    }

    //download a custom game
    private fun showDownloadDialog() {
       val boardDownloadView=LayoutInflater.from(this)
           .inflate(R.layout.dialog_download_board,null)

        showAlertDialog("Fetch memory game",boardDownloadView,View.OnClickListener {
            //Grab the text of the game name user wants to download
            val etDownloadGame = boardDownloadView.findViewById<EditText>(R.id.etDownloadGame)
            val gameToDownload = etDownloadGame.text.toString().trim()
            downloadGame(gameToDownload)
        })
    }
    private fun setupBoard() {

        supportActionBar?.title=gameName ?:getString(R.string.app_name)
        when(boardSize){
            BoardSize.EASY ->{
                tvNumMoves.text="Easy: 4x2"
                tvNumPairs.text="Pairs: 0/4"
            }
            BoardSize.MEDIUM ->{
                tvNumMoves.text="Medium: 6x3"
                tvNumPairs.text="Pairs: 0/9 "
            }
            BoardSize.HARD ->{
                tvNumMoves.text="Hard: 7x4"
                tvNumPairs.text="Pairs: 0/14"
            }
        }
        tvNumPairs.setTextColor(ContextCompat.getColor(this,R.color.color_progress_none))

        memoryGame =MemoryGame(boardSize,customGameImages)

        adapter=MemoryBoardAdapter(
            this,boardSize,memoryGame.cards,object :MemoryBoardAdapter.CardClickListener{
                override fun onCardClicked(position: Int) {
                    updateGameWithFlip(position){

                    }
                }
            })
        rvBoard.adapter=adapter
        rvBoard.setHasFixedSize(true)
        rvBoard.layoutManager= GridLayoutManager(this,boardSize.getWidth());
    }

    private fun updateGameWithFlip(position: Int, function: () -> Unit) {
        //Error Checking

        if(memoryGame.haveWonGame()){
            //alert the user of an invalid move
            Snackbar.make(clRoot,"You already won!!",Snackbar.LENGTH_LONG).show()
            return
        }
        if(memoryGame.isCardFaceUp(position)){
            //Alert the user of an invalid move
            Snackbar.make(clRoot,"Invalid move!",Snackbar.LENGTH_SHORT).show()
            return
        }

        //Actually flipping the card
        if(memoryGame.flipCard(position)){
            Log.i(TAG,"Found a match! Num pairs found :${memoryGame.numPairsFound}")

            val color=ArgbEvaluator().evaluate(
                memoryGame.numPairsFound.toFloat()/boardSize.getNumPairs(),
                ContextCompat.getColor(this,R.color.color_progress_none),
                ContextCompat.getColor(this,R.color.color_progress_full)
            ) as Int
            tvNumPairs.setTextColor(color)
            tvNumPairs.text="Pairs: ${memoryGame.numPairsFound}/${boardSize.getNumPairs()}"

            if(memoryGame.haveWonGame()){
                Snackbar.make(clRoot,"You won! Congratulations.",Snackbar.LENGTH_LONG).show()
                //launching confetti after winning the game
                CommonConfetti.rainingConfetti(clRoot, intArrayOf(Color.YELLOW,Color.GREEN,Color.MAGENTA)).oneShot()
            }
        }
        tvNumMoves.text="Moves: ${memoryGame.getNumMoves()}"
        adapter.notifyDataSetChanged()
    }
}