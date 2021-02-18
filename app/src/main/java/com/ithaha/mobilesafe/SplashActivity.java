package com.ithaha.mobilesafe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import net.tsz.afinal.FinalHttp;
import net.tsz.afinal.http.AjaxCallBack;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.widget.TextView;
import android.widget.Toast;

import com.ithaha.mobilesafe.utils.StreamTools;

/**
 * 程序启动页面
 * 功能：
 * 		1.完成相关数据库的加载
 * 		2.版本更新的查询
 * 		3.快捷图标的建立
 * @author hello
 *
 */
public class SplashActivity extends AppCompatActivity {

	protected static final int ENTER_HOME = 0;
	protected static final int SHOW_UPDATE_DIALOG = 1;
	protected static final int NETWORK_ERROR = 2;
	protected static final int JSON_ERROR = 3;
	private TextView tv_splash_version;
	protected String TAG = "SplashActivity";
	private TextView tv_update_info;

	private String description;
	private String apkurl;
	private SharedPreferences sp;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_splash);

		// 用于显示版本信息
		tv_splash_version = (TextView) findViewById(R.id.tv_splash_version);
		tv_splash_version.setText("版本号:" + getVersion());
		tv_update_info = (TextView) findViewById(R.id.tv_update_info);

		sp = getSharedPreferences("config", MODE_PRIVATE);
		boolean update = sp.getBoolean("update", false);

		// 快捷图标的创建
		installShortCut();

		// 拷贝数据库
		copyDB("address.db");
		copyDB("antivirus.db");

		if(update) {
			// 检查升级
			checkUpdate();
		} else {
			// 自动升级关闭
			handler.postDelayed(new Runnable() {
				@Override
				public void run() {
					enterHome();
				}
			}, 2000);
		}

		// 进入主页的动画效果
		AlphaAnimation aa = new AlphaAnimation(0.2f, 1.0f);
		aa.setDuration(500);

		findViewById(R.id.rl_root_splash).startAnimation(aa);
	}

	/**
	 * 创建快捷图标
	 */
	private void installShortCut() {
		boolean shortcut = sp.getBoolean("shortcut", false);
		if(shortcut) {
			return ;
		}

		Intent intent = new Intent();
		intent.setAction("com.android.launcher.action.INSTALL_SHORTCUT");
		// 快捷方式，要包含3个重要信息  1，名称 2，图标 3，干什么事情
		// 1.名称
		intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, "手机小卫士");
		// 2.图标
		intent.putExtra(Intent.EXTRA_SHORTCUT_ICON, BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher));
		// 3.指定意图
		Intent shortcutIntent = new Intent();
		// action只能指定一个， category可以指定多个
		shortcutIntent.setAction("android.intent.action.MAIN");
		// Add a new category to the intent.
		shortcutIntent.addCategory("android.intent.category.LAUNCHER");
		//
		shortcutIntent.setClassName(getPackageName(), "com.ithaha.mobilesafe.SplashActivity");
		intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);

		// 用广播的方式启动意图
		sendBroadcast(intent);

		Editor edit = sp.edit();
		edit.putBoolean("shortcut", true);
		edit.commit();
	}

	/**
	 * 拷贝数据库
	 */
	private void copyDB(String filename) {
		// 只用拷贝一次，就不重复拷贝了

		// path 把address.db这个数据库拷贝到data/data/<包名>/files/address.db
		try {
			File file = new File(getFilesDir(),filename);
			if(file.exists() && file.length() > 0) {
				// 已经存在了

			} else {
				// 还不存在
				InputStream is = getAssets().open(filename);
				FileOutputStream fos = new FileOutputStream(file);
				byte[] buffer = new byte[1024];
				int len = 0;
				while((len = is.read(buffer)) != -1) {
					fos.write(buffer, 0, len);
				}
				fos.close();
				is.close();
			}

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	private Handler handler = new Handler(){

		@Override
		public void handleMessage(Message msg) {
			super.handleMessage(msg);
			switch (msg.what) {
				case SHOW_UPDATE_DIALOG:		// 显示升级对话框
					Log.i(TAG, "显示升级的对话框");
					showUpdateDialog();
					break;

				case ENTER_HOME:				// 进入主页面
					enterHome();
					break;

				case NETWORK_ERROR:				// 网络出错
					enterHome();
					Toast.makeText(getApplicationContext(), "网络异常", Toast.LENGTH_SHORT).show();
					break;

				case JSON_ERROR:				// JSON解析出错
					enterHome();
					Toast.makeText(SplashActivity.this, "JSON解析出错", Toast.LENGTH_SHORT).show();
					break;
				default:
					break;
			}
		}
	};

	/**
	 * 弹出升级对话框
	 */
	private void showUpdateDialog() {
		// this = SplashActivity.this
		AlertDialog.Builder builder = new Builder(this);
		builder.setTitle("提醒升级");
		builder.setMessage(description);

//		builder.setCancelable(false);		// 强制升级
		// 触摸其他地方，将直接进入首页
		builder.setOnCancelListener(new OnCancelListener() {

			@Override
			public void onCancel(DialogInterface dialog) {
				enterHome();
				dialog.dismiss();
			}
		});

		builder.setPositiveButton("立即升级", new OnClickListener() {

			@Override
			public void onClick(DialogInterface dialog, int which) {
				// 下载apk，并且替换安装
				if(Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
					// sdcard 存在

					// afinal
					FinalHttp finalhttp = new FinalHttp();
					finalhttp.download(apkurl, Environment.getExternalStorageDirectory().getAbsolutePath()+"/mobilesafe2.0.apk", new AjaxCallBack<File>() {

						@Override
						public void onFailure(Throwable t, int errorNo,
											  String strMsg) {
							t.printStackTrace();
							Toast.makeText(getApplicationContext(), "下载失败", Toast.LENGTH_LONG).show();
							super.onFailure(t, errorNo, strMsg);
						}

						@Override
						public void onLoading(long count, long current) {
							super.onLoading(count, current);
							tv_update_info.setVisibility(View.VISIBLE);
							// 当前下载百分比

							tv_update_info.setText("下载进度:" + current + "/" + count);
						}

						@Override
						public void onSuccess(File t) {
							super.onSuccess(t);
							installAPK(t);
						}

					});
				} else {
					Toast.makeText(getApplicationContext(), "没有SD卡，无法正确下载", Toast.LENGTH_SHORT).show();
				}
			}
		});

		builder.setNegativeButton("下次再说", new OnClickListener() {

			@Override
			public void onClick(DialogInterface dialog, int which) {
				// 对话框消失，进入主页面
				dialog.dismiss();
				enterHome();
			}
		});

		builder.show();
	}

	/**
	 * 进入主页面
	 */
	private void enterHome() {
		Intent intent = new Intent(this,HomeActivity.class);
		startActivity(intent);
		// 关闭当前页面
		finish();
	}

	/**
	 * 检查是否 有新版本
	 */
	private void checkUpdate() {

		new Thread(){

			public void run() {
				// Return a new Message instance from the global pool.
				Message msg = Message.obtain();
//				Message msg = new Message();
				long startTime = System.currentTimeMillis();
				try {
					// URL
					URL url = new URL(getString(R.string.serverurl));

					// 联网
					HttpURLConnection conn = (HttpURLConnection) url.openConnection();
					conn.setRequestMethod("GET");
					conn.setReadTimeout(400);
					conn.setConnectTimeout(400);
					int responseCode = conn.getResponseCode();
					if(responseCode == 200) {
						// 联网成功
						InputStream is = conn.getInputStream();
						// 把流转换成字符串
						String result = StreamTools.readFromStream(is);
						Log.i(TAG , "联网成功: " + result);

						// json解析
						JSONObject obj = new JSONObject(result);
						String version = (String) obj.get("version");
						description = (String) obj.get("description");
						apkurl = (String) obj.get("apkurl");

						// 校验是否有新版本
						if(getVersion().equals(version)) {
							// 版本一致，没有新版本,进入主页面
							msg.what = ENTER_HOME;

						} else {
							// 有新版本，弹出升级对话框
							msg.what = SHOW_UPDATE_DIALOG;
						}
					}
				} catch (IOException e) {
					e.printStackTrace();
					msg.what = NETWORK_ERROR;
				} catch (JSONException e) {
					e.printStackTrace();
					msg.what = JSON_ERROR;
				} finally {
					long endTime = System.currentTimeMillis();
					// 花了多少时间了
					long dTime = endTime - startTime;
					// 需要停留2s
					if(dTime < 2000) {
						try {
							Thread.sleep(2000 - dTime);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}

					handler.sendMessage(msg);
				}
			};
		}.start();

	}

	/**
	 * 得到应用程序的版本信息
	 */
	private String getVersion() {

		// 用来管理手机的apk Return PackageManager instance to find global package information.
		PackageManager pm = getPackageManager();

		try {
			// 得到指定apk的功能清单文件
			PackageInfo packageInfo = pm.getPackageInfo(getPackageName(), 0);
			String versionName = packageInfo.versionName;
			return versionName;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * 安装apk
	 * @param t
	 */
	private void installAPK(File t) {
		Intent intent = new Intent();
		intent.setAction("android.intent.action.VIEW");
		intent.addCategory("android.intent.category.DEFAULT");
		intent.setDataAndType(Uri.fromFile(t), "application/vnd.android.package-archive");
		startActivity(intent);
	}
}
