package com.yuchting.yuchberry.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;

import net.rim.device.api.compress.GZIPInputStream;
import net.rim.device.api.compress.GZIPOutputStream;


public class sendReceive extends Thread{
	
	OutputStream		m_socketOutputStream = null;
	InputStream			m_socketInputStream = null;
	
	private Vector		m_unsendedPackage 		= new Vector();
	private Vector		m_unprocessedPackage 	= new Vector();
	
	boolean			m_closed				= false;
	
	int					m_keepliveCounter		= 0;
		
	public sendReceive(OutputStream _socketOut,InputStream _socketIn){
		m_socketOutputStream = _socketOut;
		m_socketInputStream = _socketIn;
		
		start();
	}
		
	//! send buffer
	public synchronized void SendBufferToSvr(byte[] _write,boolean _sendImm)throws Exception{	
		m_unsendedPackage.addElement(_write);
		
		if(_sendImm){
			SendBufferToSvr_imple(PrepareOutputData());
			
			synchronized (this) {
				m_keepliveCounter = 0;
			}
			
		}
	}
	
	public void CloseSendReceive(){
		
		if(m_closed == false){
			m_closed = true;
			
			m_unsendedPackage.removeAllElements();
			m_unprocessedPackage.removeAllElements();
			
			interrupt();
			
			try{
				m_socketOutputStream.close();
				m_socketInputStream.close();
			}catch(Exception _e){}
			
	
			while(isAlive()){
				try{
					sleep(10);
				}catch(Exception _e){};			
			}	
		}		
	}
	
	private synchronized byte[] PrepareOutputData()throws Exception{
		
		if(m_unsendedPackage.isEmpty()){
			return null;
		}
		
		ByteArrayOutputStream t_stream = new ByteArrayOutputStream();
				
		for(int i = 0;i < m_unsendedPackage.size();i++){
			byte[] t_package = (byte[])m_unsendedPackage.elementAt(i);	
			
			WriteInt(t_stream, t_package.length);
						
			t_stream.write(t_package);
		}
		
		m_unsendedPackage.removeAllElements();
		
		return t_stream.toByteArray();
	}
	
	//! send buffer implement
	private void SendBufferToSvr_imple(byte[] _write)throws Exception{
		
		if(_write == null){
			return;
		}		
		
		OutputStream os = m_socketOutputStream;
		
		ByteArrayOutputStream zos = new ByteArrayOutputStream();
		GZIPOutputStream zo = new GZIPOutputStream(zos);
		zo.write(_write);
		zo.close();	
		
		byte[] t_zipData = zos.toByteArray();
		
		if(t_zipData.length > _write.length){
			// if the ZIP data is large than original length
			// NOT convert
			//
			WriteInt(os,_write.length << 16);
			os.write(_write);
			os.flush();
			
		}else{
			WriteInt(os,(_write.length << 16) | t_zipData.length);
			os.write(t_zipData);
			os.flush();
		}
				
	}
	
	public void run(){
		
		try{
			boolean t_keeplive = false;
			
			while(!m_closed){
				SendBufferToSvr_imple(PrepareOutputData());
				sleep(5000);				
				
				
				synchronized (this){
					if(++m_keepliveCounter > 100){
						m_keepliveCounter = 0;
						t_keeplive = true;
					}
				}				
				
				if(t_keeplive){
					t_keeplive = false;
					
					ByteArrayOutputStream t_os = new ByteArrayOutputStream();
					WriteInt(t_os, 1);
					t_os.write(msg_head.msgKeepLive);
					
					SendBufferToSvr_imple(t_os.toByteArray());
				}
			}
			
		}catch(Exception _e){
			try{
				m_socketOutputStream.close();
				m_socketInputStream.close();	
			}catch(Exception e){}
		}
	}

	//! recv buffer
	public byte[] RecvBufferFromSvr()throws Exception{
		
		if(!m_unprocessedPackage.isEmpty()){
			byte[] t_ret = (byte[])m_unprocessedPackage.elementAt(0);
			m_unprocessedPackage.removeElementAt(0);
			
			return t_ret;
		}
		
		synchronized (this) {
			m_keepliveCounter = 0;
		}
		
		InputStream in = m_socketInputStream;

		int t_len = ReadInt(in);
		
		final int t_ziplen = t_len & 0x0000ffff;
		final int t_orglen = t_len >>> 16;
				
		byte[] t_orgdata = new byte[t_orglen];
				
		if(t_ziplen == 0){
			
			ForceReadByte(in, t_orgdata, t_orglen);
			
		}else{
			
			byte[] t_zipdata = new byte[t_ziplen];
			
			ForceReadByte(in, t_zipdata, t_ziplen);
			
			GZIPInputStream zi	= new GZIPInputStream(
										new ByteArrayInputStream(t_zipdata));

			ForceReadByte(zi,t_orgdata,t_orglen);
			
			zi.close();
		}
		
		byte[] t_ret = ParsePackage(t_orgdata);
		t_orgdata = null;
				
		
		return t_ret;
	}
	
	private byte[] ParsePackage(byte[] _wholePackage)throws Exception{
		
		ByteArrayInputStream t_packagein = new ByteArrayInputStream(_wholePackage);
		int t_len = ReadInt(t_packagein);
					
		byte[] t_ret = new byte[t_len];
		t_packagein.read(t_ret,0,t_len);
		
		t_len += 4;
		
		while(t_len < _wholePackage.length){
			
			final int t_packageLen = ReadInt(t_packagein); 
			
			byte[] t_package = new byte[t_packageLen];
			
			t_packagein.read(t_package,0,t_packageLen);
			t_len += t_packageLen + 4;
			
			m_unprocessedPackage.addElement(t_package);			
		}		
		
		return t_ret;		
	}
	// static function to input and output integer
	//
	static public void WriteStringVector(OutputStream _stream,Vector _vect)throws Exception{
		
		final int t_size = _vect.size();
		WriteInt(_stream,t_size);
		
		for(int i = 0;i < t_size;i++){
			WriteString(_stream,(String)_vect.elementAt(i));
		}
	}
	
	static public void WriteString(OutputStream _stream,String _string)throws Exception{
		final byte[] t_strByte = _string.getBytes("GB2312");
		WriteInt(_stream,t_strByte.length);
		if(t_strByte.length != 0){
			_stream.write(t_strByte);
		}
	}
	
		
	static public void ReadStringVector(InputStream _stream,Vector _vect)throws Exception{
		
		_vect.removeAllElements();
		
		final int t_size = ReadInt(_stream);
				
		for(int i = 0;i < t_size;i++){
			_vect.addElement(ReadString(_stream));
		}
	}
	
	static public String ReadString(InputStream _stream)throws Exception{
		
		final int len = ReadInt(_stream);
		
		if(len != 0){
			byte[] t_buffer = new byte[len];
			
			ForceReadByte(_stream,t_buffer,len);

			return new String(t_buffer,"GB2312");
		}
		
		return new String("");
		
	}
	
	static public int ReadInt(InputStream _stream)throws Exception{
		return _stream.read() | (_stream.read() << 8) | (_stream.read() << 16) | (_stream.read() << 24);
	}

	static public void WriteInt(OutputStream _stream,int _val)throws Exception{
		_stream.write(_val);
		_stream.write(_val >>> 8 );
		_stream.write(_val >>> 16);
		_stream.write(_val >>> 24);
	}
	
	static public void ForceReadByte(InputStream _stream,byte[] _buffer,int _readLen)throws Exception{
		int t_readIndex = 0;
		int t_counter = 0;
		
		while(_readLen > t_readIndex){
			final int t_c = _stream.read(_buffer,t_readIndex,_readLen - t_readIndex);
			if(t_c > 0){
				t_readIndex += t_c;
			}else{
				t_counter++;
				
				if(t_counter > 10){
					throw new Exception("FroceReadByte failed to read " + _readLen );
				}
			}		
		}
	}
	
}

