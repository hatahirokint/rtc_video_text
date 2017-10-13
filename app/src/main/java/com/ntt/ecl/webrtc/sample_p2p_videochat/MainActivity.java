package com.ntt.ecl.webrtc.sample_p2p_videochat;

import android.Manifest;
import android.app.Activity;
import android.app.FragmentManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;

import io.skyway.Peer.Browser.Canvas;
import io.skyway.Peer.Browser.MediaConstraints;
import io.skyway.Peer.Browser.MediaStream;
import io.skyway.Peer.Browser.Navigator;
import io.skyway.Peer.CallOption;
import io.skyway.Peer.ConnectOption;
import io.skyway.Peer.DataConnection;
import io.skyway.Peer.MediaConnection;
import io.skyway.Peer.OnCallback;
import io.skyway.Peer.Peer;
import io.skyway.Peer.PeerError;
import io.skyway.Peer.PeerOption;

/**
 *
 * MainActivity.java
 * ECL WebRTC p2p video-chat sample
 *
 */


public class MainActivity extends Activity implements Runnable{
	private static final String TAG = MainActivity.class.getSimpleName();

    //MicroBridgeの設定
    private static int portNumber = 60200;
    private ServerSocket mServerSocket;
    private Socket mSock = null;

    private boolean serverActive = false;

    private Handler mHandler = new Handler();

    private OutputStreamWriter transmitData = null;
    private BufferedReader receivedData = null;

   // private String strValue = null;


	//
	// Set your APIkey and Domain
	//
	private static final String API_KEY = "3eab7117-c2ca-49ef-8c73-85d6d9439b47";
	private static final String DOMAIN = "hiroki.hatahata";


	private Peer			_peer;
	private DataConnection _dataConnection;//

	private MediaStream		_localStream;
	private MediaStream		_remoteStream;
	private MediaConnection	 _mediaConnection;

	private String			_strOwnId;
	private boolean			_bConnected;

	private Handler			_handler;
	private TextView        _tvMessage;//


	@Override
	protected void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);
		Window wnd = getWindow();
		wnd.addFlags(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.activity_main);

		_tvMessage = (TextView) findViewById(R.id.tvMessage);//
		_handler = new Handler(Looper.getMainLooper());
		final Activity activity = this;

		//
		// Initialize Peer
		//
		PeerOption option = new PeerOption();//peerオブジェクト作成
		option.key = API_KEY;
		option.domain = DOMAIN;
		_peer = new Peer(this, option);

		//
		// Set Peer event callbacks
		//

		// OPEN
		_peer.on(Peer.PeerEventEnum.OPEN, new OnCallback() {
			@Override
			public void onCallback(Object object) {
				// Show my ID
				_strOwnId = (String) object;
				TextView tvOwnId = (TextView) findViewById(R.id.tvOwnId);
				tvOwnId.setText(_strOwnId);
				// Request permissions
				if (ContextCompat.checkSelfPermission(activity,
						Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(activity,
						Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
					ActivityCompat.requestPermissions(activity,new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},0);
				}
				else {
					// Get a local MediaStream & show it
					startLocalStream();
				}

			}
		});

		// CALL (Incoming call)データ通信でかかってきたとき
		_peer.on(Peer.PeerEventEnum.CONNECTION, new OnCallback() {
			@Override
			public void onCallback(Object object) {
				if (!(object instanceof DataConnection)){
					return;
				}

				_dataConnection = (DataConnection)object;
				setDataCallbacks();
				updateActionButtonTitle();
			}
		});

		// CALL (Incoming call)メディア通信でかかってきたとき
		_peer.on(Peer.PeerEventEnum.CALL, new OnCallback() {
			@Override
			public void onCallback(Object object) {
				Log.d(TAG, "[On/Close]");
				if (!(object instanceof MediaConnection)) {
					return;
				}

				_mediaConnection = (MediaConnection) object;
				setMediaCallbacks();
				_mediaConnection.answer(_localStream);

				_bConnected = true;
				updateActionButtonTitle();
			}
		});

		_peer.on(Peer.PeerEventEnum.CLOSE, new OnCallback()	{
			@Override
			public void onCallback(Object object) {
				Log.d(TAG, "[On/Close]");
			}
		});

		_peer.on(Peer.PeerEventEnum.DISCONNECTED, new OnCallback() {
			@Override
			public void onCallback(Object object) {
				Log.d(TAG, "[On/Disconnected]");
			}
		});

		_peer.on(Peer.PeerEventEnum.ERROR, new OnCallback() {
			@Override
			public void onCallback(Object object) {
				PeerError error = (PeerError) object;
				Log.d(TAG, "[On/Error]" + error);
			}
		});


		//
		// Set GUI event listeners
		//
		Button btnAction = (Button) findViewById(R.id.btnAction);
		btnAction.setEnabled(true);
		btnAction.setOnClickListener(new View.OnClickListener()	{
			@Override
			public void onClick(View v)	{
				v.setEnabled(false);

				if (!_bConnected) {
					// Select remote peer & make a call
					// 接続先のIDを表示
					showPeerIDs();
				}
				else {

					// Hang up a call
					closeRemoteStream();
					_mediaConnection.close();
					_dataConnection.close();//

				}

				v.setEnabled(true);
			}
		});

		Button switchCameraAction = (Button)findViewById(R.id.switchCameraAction);
		switchCameraAction.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v)	{
				if(null != _localStream){
					Boolean result = _localStream.switchCamera();
					if(true == result)	{
						//Success
					}
					else {
						//Failed
					}
				}
			}
		});


		Button btnSend = (Button) findViewById(R.id.btnSend);
		btnSend.setEnabled(true);
		btnSend.setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				if (_bConnected) {
					v.setEnabled(false);

					Spinner spDataType = (Spinner)findViewById(R.id.spDataType);
					int iType = spDataType.getSelectedItemPosition();
					sendData(iType);

					v.setEnabled(true);
				}
			}
		});
	}

	//カメラへのPermissionの処理
    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 0: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startLocalStream();
                }
				else {
                    Toast.makeText(this,"Failed to access the camera and microphone.\nclick allow when asked for permission.", Toast.LENGTH_LONG).show();
                }
                break;
            }
        }
    }

	@Override
	protected void onStart() {
		super.onStart();

		// Disable Sleep and Screen Lock
		Window wnd = getWindow();
		wnd.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
		wnd.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
	}

	@Override
	protected void onResume() {
		super.onResume();

		// Set volume control stream type to WebRTC audio.
		setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);

        //Microbridge
        serverActive = true;
        new Thread(this).start();
        Toast.makeText(this, "Server thread start", Toast.LENGTH_SHORT).show();
        Log.d(TAG, "Server thread started");
	}

	@Override
	protected void onPause() {
		// Set default volume control stream type.
		setVolumeControlStream(AudioManager.USE_DEFAULT_STREAM_TYPE);
		super.onPause();

        //Microbridge
        serverActive = false;
        try {
            if (mSock != null) {
                mSock.close();
            }
            mServerSocket.close();
            Log.v("Microbridge", "Socket closed");
        } catch (IOException e) {
            Log.v("Microbridge", "IOException");
        }
	}

	//Microbridge
    public void run() {
        String receivedStr;

        Log.d(TAG, "Thread started...");
        try {
            mServerSocket = new ServerSocket();
            mServerSocket.setReuseAddress(true);
            mServerSocket.bind(new InetSocketAddress(portNumber));
            Log.d(TAG, "Waiting to connect...");
            mSock = mServerSocket.accept();

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "Connected", Toast.LENGTH_SHORT).show();
                }
            });

            receivedData = new BufferedReader(new InputStreamReader(
                    mSock.getInputStream()));
            transmitData = new OutputStreamWriter(mSock.getOutputStream());

            while (serverActive) {
                if ((receivedStr = receivedData.readLine()) != null) {
                    if(receivedStr.equals("ON")){
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {

                            }
                        });
                    } else {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {

                            }
                        });
                    }
                }
            }
            receivedData = null;
            transmitData = null;
            mSock.close();
            mSock = null;

            mServerSocket.close();
            Log.v(TAG, "Socket closed");
            mHandler.post(new Runnable() {
                @Override
                public void run() {

                }
            });
        } catch (SocketException e) {
        } catch (IOException e) {
        }
    }

	@Override
	protected void onStop()	{
		// Enable Sleep and Screen Lock
		Window wnd = getWindow();
		wnd.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		wnd.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
		super.onStop();
	}

	@Override
	protected void onDestroy() {
		destroyPeer();
		super.onDestroy();
	}

	//
	// Get a local MediaStream & show it
	//
	void startLocalStream() {
		Navigator.initialize(_peer);
		MediaConstraints constraints = new MediaConstraints();
		_localStream = Navigator.getUserMedia(constraints);

		Canvas canvas = (Canvas) findViewById(R.id.svLocalView);
		_localStream.addVideoRenderer(canvas,0);
	}


	//
	// Set callbacks for MediaConnection.MediaEvents
	//
	void setDataCallbacks() {

		_dataConnection.on(DataConnection.DataEventEnum.OPEN, new OnCallback() {
			@Override
			public void onCallback(Object object) {
				_bConnected = true;
				updateActionButtonTitle();
				appendLog("Connected.");
			}
		});

		_dataConnection.on(DataConnection.DataEventEnum.CLOSE, new OnCallback()	{
			@Override
			public void onCallback(Object object) {
				_bConnected = false;
				updateActionButtonTitle();
				unsetDataCallbacks();
				_dataConnection = null;
			}
		});

		_dataConnection.on(DataConnection.DataEventEnum.DATA, new OnCallback()	{
			@Override
			public void onCallback(Object object) {
				String strValue = null;

                if (object instanceof String) {
                    strValue = (String) object;

                } else {
                    strValue = "DataType: " + object.getClass().getSimpleName();
                }
                appendLog("Remote:"+ strValue);

                try {
                    Log.v(TAG, "Output to Buffer");
                    transmitData.write((String)object);
                    transmitData.flush();
                } catch (SocketException e) {
                    Toast.makeText(getApplicationContext(),"SocketException",Toast.LENGTH_LONG).show();
                } catch (IOException e) {
                    e.printStackTrace();
                }
			}
		});

		_dataConnection.on(DataConnection.DataEventEnum.ERROR, new OnCallback()	{
			@Override
			public void onCallback(Object object) {
				PeerError error = (PeerError) object;
				Log.d(TAG, "[On/MediaError]" + error);
			}
		});

	}


	//
	// Set callbacks for MediaConnection.MediaEvents
	//
	void setMediaCallbacks() {

		_mediaConnection.on(MediaConnection.MediaEventEnum.STREAM, new OnCallback() {
			@Override
			public void onCallback(Object object) {
				_remoteStream = (MediaStream) object;
				Canvas canvas = (Canvas) findViewById(R.id.svRemoteView);
				_remoteStream.addVideoRenderer(canvas,0);
			}
		});

		_mediaConnection.on(MediaConnection.MediaEventEnum.CLOSE, new OnCallback()	{
			@Override
			public void onCallback(Object object) {
				closeRemoteStream();
				_bConnected = false;
				updateActionButtonTitle();
			}
		});

		_mediaConnection.on(MediaConnection.MediaEventEnum.ERROR, new OnCallback()	{
			@Override
			public void onCallback(Object object) {
				PeerError error = (PeerError) object;
				Log.d(TAG, "[On/MediaError]" + error);
			}
		});

	}

	//
	// Clean up objects
	//
	private void destroyPeer() {
		closeRemoteStream();

		if (null != _dataConnection)	{
			if (_dataConnection.isOpen()) {
				_dataConnection.close();
			}
			unsetDataCallbacks();
		}

		if (null != _localStream) {
			Canvas canvas = (Canvas) findViewById(R.id.svLocalView);
			_localStream.removeVideoRenderer(canvas,0);
			_localStream.close();
		}

		if (null != _mediaConnection)	{
			if (_mediaConnection.isOpen()) {
				_mediaConnection.close();
			}
			unsetMediaCallbacks();
		}

		Navigator.terminate();

		if (null != _peer) {
			unsetPeerCallback(_peer);
			if (!_peer.isDisconnected()) {
				_peer.disconnect();
			}

			if (!_peer.isDestroyed()) {
				_peer.destroy();
			}

			_peer = null;
		}
	}

	//
	// Unset callbacks for PeerEvents
	//
	void unsetPeerCallback(Peer peer) {
		if(null == _peer){
			return;
		}

		peer.on(Peer.PeerEventEnum.OPEN, null);
		peer.on(Peer.PeerEventEnum.CONNECTION, null);
		peer.on(Peer.PeerEventEnum.CALL, null);
		peer.on(Peer.PeerEventEnum.CLOSE, null);
		peer.on(Peer.PeerEventEnum.DISCONNECTED, null);
		peer.on(Peer.PeerEventEnum.ERROR, null);
	}

	//
	// Unset callbacks for DataConnection.DataEvents
	//
	void unsetDataCallbacks() {
		if(null == _dataConnection){
			return;
		}

		_dataConnection.on(DataConnection.DataEventEnum.OPEN, null);
		_dataConnection.on(DataConnection.DataEventEnum.CLOSE, null);
		_dataConnection.on(DataConnection.DataEventEnum.DATA, null);
		_dataConnection.on(DataConnection.DataEventEnum.ERROR, null);
	}


	//
	// Unset callbacks for MediaConnection.MediaEvents
	//
	void unsetMediaCallbacks() {
		if(null == _mediaConnection){
			return;
		}

		_mediaConnection.on(MediaConnection.MediaEventEnum.STREAM, null);
		_mediaConnection.on(MediaConnection.MediaEventEnum.CLOSE, null);
		_mediaConnection.on(MediaConnection.MediaEventEnum.ERROR, null);
	}

	//
	// Close a remote MediaStream
	//
	void closeRemoteStream(){
		if (null == _remoteStream) {
			return;
		}

		Canvas canvas = (Canvas) findViewById(R.id.svRemoteView);
		_remoteStream.removeVideoRenderer(canvas,0);
		_remoteStream.close();
	}

	//
	// Create a MediaConnection
	//
	void onPeerSelected(String strPeerId) {
		if (null == _peer) {
			return;
		}

		if (null != _dataConnection) {
			_dataConnection.close();
		}

		ConnectOption option = new ConnectOption();
		option.label = "chat";
		_dataConnection = _peer.connect(strPeerId, option);//データ接続開始

		if (null != _dataConnection) {
			setDataCallbacks();
			_bConnected = true;
		}

		if (null != _mediaConnection) {
			setMediaCallbacks();
			_mediaConnection.close();
		}

		CallOption option2 = new CallOption();
		option.label = "chat";
		_mediaConnection = _peer.call(strPeerId, _localStream, option2);//メディア接続開始

		if (null != _mediaConnection) {
			setMediaCallbacks();
			_bConnected = true;
		}

		updateActionButtonTitle();
	}

	//
	// Listing all peers
	//
	void showPeerIDs() {
		if ((null == _peer) || (null == _strOwnId) || (0 == _strOwnId.length())) {
			Toast.makeText(this, "Your PeerID is null or invalid.", Toast.LENGTH_SHORT).show();
			return;
		}

		// Get all IDs connected to the server
		final Context fContext = this;
		_peer.listAllPeers(new OnCallback() {
			@Override
			public void onCallback(Object object) {
				if (!(object instanceof JSONArray)) {
					return;
				}

				JSONArray peers = (JSONArray) object;
				ArrayList<String> _listPeerIds = new ArrayList<>();
				String peerId;

				// Exclude my own ID
				for (int i = 0; peers.length() > i; i++) {
					try {
						peerId = peers.getString(i);
						if (!_strOwnId.equals(peerId)) {
							_listPeerIds.add(peerId);
						}
					} catch(Exception e){
						e.printStackTrace();
					}
				}

				// Show IDs using DialogFragment
				if (0 < _listPeerIds.size()) {
					FragmentManager mgr = getFragmentManager();
					PeerListDialogFragment dialog = new PeerListDialogFragment();
					dialog.setListener(
							new PeerListDialogFragment.PeerListDialogFragmentListener() {
								@Override
								public void onItemClick(final String item) {
									_handler.post(new Runnable() {
										@Override
										public void run() {
											onPeerSelected(item);
										}
									});
								}
							});
					dialog.setItems(_listPeerIds);
					dialog.show(mgr, "peerlist");
				}
				else{
					Toast.makeText(fContext, "PeerID list (other than your ID) is empty.", Toast.LENGTH_SHORT).show();
				}
			}
		});

	}

	//
	// Update actionButton title
	//
	void updateActionButtonTitle() {
		_handler.post(new Runnable() {
			@Override
			public void run() {
				Button btnAction = (Button) findViewById(R.id.btnAction);
				if (null != btnAction) {
					if (false == _bConnected) {
						btnAction.setText("Connect");
					} else {
						btnAction.setText("Disconnect");
					}
				}
			}
		});
	}

	//
	// Send Data
	//
	void sendData(int type){
		if(_dataConnection != null){

		}
		Boolean bResult = false;
		String strMsg = "";
		switch(type){
            case 0:{
                String strData = "48";
                bResult = _dataConnection.send(strData);
                strMsg = strData;
                break;
            }

            case 1:{
                String strData = "49";
                bResult = _dataConnection.send(strData);
                strMsg = strData;
                break;
            }

            case 2:{
                String strData = "50";
                bResult = _dataConnection.send(strData);
                strMsg = strData;
                break;
            }

            case 3: {
                String strData = "51";
                bResult = _dataConnection.send(strData);
                strMsg = strData;
                break;
            }

            case 4: {
                String strData = "52";
                bResult = _dataConnection.send(strData);
                strMsg = strData;
                break;
            }

            case 5: {
                String strData = "53";
                bResult = _dataConnection.send(strData);
                strMsg = strData;
                break;
            }
            case 6: {
                String strData = "54";
                bResult = _dataConnection.send(strData);
                strMsg = strData;
                break;
            }

            case 7: {
                String strData = "55";
                bResult = _dataConnection.send(strData);
                strMsg = strData;
                break;
            }

            default:{
                break;
            }
		}

		if(bResult) {
			appendLog("You:" + strMsg);
		}

	}

	//
	// Append a string to tvMessage
	//
	void appendLog(String logText){
		_tvMessage.append(logText+"\n");
	}

}
