package com.wmz7year.synyed.net.proroc;

import static com.wmz7year.synyed.constant.RedisProtocolConstant.*;
import static com.wmz7year.synyed.constant.RedisCommandSymbol.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.wmz7year.synyed.exception.RedisProtocolException;
import com.wmz7year.synyed.exception.RedisRDBException;
import com.wmz7year.synyed.packet.redis.RedisArraysPacket;
import com.wmz7year.synyed.packet.redis.RedisBulkStringPacket;
import com.wmz7year.synyed.packet.redis.RedisDataBaseTransferPacket;
import com.wmz7year.synyed.packet.redis.RedisErrorPacket;
import com.wmz7year.synyed.packet.redis.RedisIntegerPacket;
import com.wmz7year.synyed.packet.redis.RedisPacket;
import com.wmz7year.synyed.packet.redis.RedisSimpleStringPacket;

/**
 * Redis数据管道解析器<br>
 * 将socket中读取的byte数据流进行初步处理<br>
 * 如断包、粘包等<br>
 * 每个redis命令都以\r\n结尾 也就是0x0D 0x0A<br>
 * 该解析器为全局唯一的对象
 * 
 * FIXME:重构该类
 * 
 * 
 * @Title: RedisProtocolParser.java
 * @Package com.wmz7year.synyed.parser
 * @author jiangwei (ydswcy513@gmail.com)
 * @date 2015年12月10日 下午2:57:26
 * @version V1.0
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
public class RedisProtocolParser {
	private static final Logger logger = LoggerFactory.getLogger(RedisProtocolParser.class);

	/**
	 * 缓冲区大小 1M
	 */
	private int BUFFERSIZE = 1024 * 1024;
	/**
	 * 缓冲区
	 */
	private byte[] buffer = new byte[BUFFERSIZE];
	/**
	 * 缓冲区中写入的位置
	 */
	private int limit = 0;
	/**
	 * 缓冲区读取过的位置
	 */
	private int readFlag = 0;
	/**
	 * 缓冲区最大长度
	 */
	private int maxLength = buffer.length;

	/**
	 * 判断缓冲区是否写满的标志位
	 */
	private boolean isFull = false;
	/**
	 * 当前解析中的数据包
	 */
	private byte[] currentPacket;
	/**
	 * 当前处理中的数据包写入位置
	 */
	int currentPacketWriteFlag = 0;
	/**
	 * 读取数据包的自动扩容数量
	 */
	private int readInc = 512;
	/**
	 * 复合类型字符串长度是否读取过的标识为<br>
	 * 只在复合类型字符串响应数据时才使用
	 */
	private boolean bulkCrLfReaded = false;
	/**
	 * 复合类型字符串长度是否为正数的标识为<br>
	 * 0为未读取 1为正数 -1为负数
	 */
	private long bulkNeg = 0;
	/**
	 * 复合类型字符串的长度
	 */
	private long bulkLength = -2;
	/**
	 * 已经读取的复合类型字符串的长度
	 */
	private long readedBulkLength = 0;
	/**
	 * 解析出的数据包列表
	 */
	private List<RedisPacket> packets = new ArrayList<RedisPacket>();
	/**
	 * 当前数据包类型
	 */
	private byte currentPacketType;

	/**
	 * 数组类型长度是否读取过的标识位<br>
	 * 只有数组类型数据包读取的时候会使用
	 */
	private boolean arrayCrLfReaded = false;

	/**
	 * 数组数据长度
	 */
	private long arrayLength = -2;

	/**
	 * 当前处理中的数组包
	 */
	private RedisArraysPacket arrayPacket;

	/**
	 * 判断是否是数据传输的包
	 */
	private boolean isDatabaseTrancefer = false;

	/**
	 * 判断是否检查过数据是否是数据
	 */
	private boolean isDatabaseTranceferChecked = false;

	/**
	 * 数据传输包的临时文件
	 */
	private RandomAccessFile tempRandomAccessFile;

	/**
	 * 数据传输包的临时文件管道
	 */
	private FileChannel tempFileChannel;

	/**
	 * 临时文件对象
	 */
	private File tempFile;

	/**
	 * 解析Redis数据包的方法<br>
	 * 
	 * @param byteBuffer
	 *            需要解析的数据包内容
	 * @throws RedisProtocolException
	 *             当解析过程中出现问题则抛出该异常
	 */
	public void read(ByteBuffer byteBuffer) throws RedisProtocolException {
		// 拷贝数据到缓冲区的方法
		copyDataToBuffer(byteBuffer);

		// 解析数据包的方法
		while (true) {
			// 当没有数据时退出读取
			if (!hasRemaining()) {
				break;
			}
			RedisPacket packet = decodePacket();
			if (packet != null) {
				// 清理临时文件内容
				if (isDatabaseTrancefer) {
					cleanTempFile();
				}
				// 清空数据传输包校验
				this.isDatabaseTranceferChecked = false;
				this.isDatabaseTrancefer = false;

				this.packets.add(packet);
			}
		}
	}

	/**
	 * 拷贝数据到缓冲区的方法<br>
	 * 循环读写一个固定的缓冲区，当数据量过大的时候会对缓冲区自动扩容<br>
	 * 
	 * @param byteBuffer
	 *            需要拷贝的数据
	 * @throws RedisProtocolException
	 *             拷贝过程中发生问题抛出该异常
	 */
	private void copyDataToBuffer(ByteBuffer byteBuffer) throws RedisProtocolException {
		try {
			// 获取数据包内容的长度
			int dataLength = byteBuffer.limit();
			if (logger.isDebugEnabled()) {
				logger.debug("Recv " + dataLength + " bytes data");
			}

			// 创建对应大小的缓冲区
			byte[] dataBuffer = new byte[dataLength];
			// 读取数据
			byteBuffer.get(dataBuffer);

			// 获取当前缓冲区剩余可用空间
			int currentCapacity = getCurrentCapacity();
			if (logger.isDebugEnabled()) {
				logger.debug("Current buffer available " + currentCapacity + " byte");
			}

			// 判断缓冲区容量是否可以装得下此次来的数据 如果不能则扩充数组长度
			if (currentCapacity < dataLength) {
				byte[] tempBuffer = new byte[maxLength + (dataLength - currentCapacity)];
				if (limit > readFlag) { // 判断是否是连续拷贝
					System.arraycopy(buffer, readFlag, tempBuffer, 0, (limit - currentCapacity));
					limit = limit - readFlag; // 重置写入标识位
				} else if (limit == readFlag) { // 读取与写入一样 重置缓冲区
					limit = 0; // 重置写入标识位
				} else { // 分开拷贝
					System.arraycopy(buffer, readFlag, tempBuffer, 0, (maxLength - readFlag));
					System.arraycopy(buffer, 0, tempBuffer, (maxLength - readFlag), limit);
				}
				buffer = null;
				buffer = tempBuffer;
				tempBuffer = null;
				readFlag = 0; // 复位读取的地方
				maxLength = buffer.length;
			}

			// 判断是否需要中断复制
			if ((maxLength - limit) < dataLength) {
				// 分段的长度
				int flag = (maxLength - limit);
				if (flag == 0) {
					readFlag = 0;
				}
				// 第一次复制 从可以写入位置一直复制到数组结束
				System.arraycopy(dataBuffer, 0, buffer, limit, flag);
				// 第二次复制 从第一个元素开始写直到写完
				System.arraycopy(dataBuffer, flag, buffer, 0, dataLength - flag);
				limit = dataLength - flag;
				isFull = (limit == readFlag); // 判断是否为写满
			} else {
				System.arraycopy(dataBuffer, 0, buffer, limit, dataLength);
				// 更新limit位置
				limit = dataLength + limit;
				isFull = (limit == readFlag); // 判断是否为写满
			}

			dataBuffer = null;
		} catch (Exception e) {
			throw new RedisProtocolException("拷贝数据异常", e);
		}
	}

	/**
	 * 获取当前缓冲区可用空间的方法
	 * 
	 * @return 当前缓冲区可用空间
	 * @throws RedisProtocolException
	 *             当出现问题时抛出该异常
	 */
	private int getCurrentCapacity() throws RedisProtocolException {
		int currentCapacity = 0;
		if (limit > readFlag) {
			currentCapacity = (maxLength - limit) + readFlag;
		} else if (limit == readFlag) {
			currentCapacity = maxLength;
		} else {
			currentCapacity = maxLength - ((maxLength - readFlag) + limit);
		}
		return currentCapacity;
	}

	/**
	 * 解析一个数据包的方法<br>
	 * 当出现分包断包情况时返回null
	 * 
	 * @return 解析出的数据包对象
	 * @throws RedisProtocolException
	 *             当解析出现异常时抛出该数据包
	 */
	private RedisPacket decodePacket() throws RedisProtocolException {
		try {
			// 判断数据是否读取完了
			if (!hasRemaining()) {
				return null;
			}

			// 判断是否有解析中的包 如果没有则初始化一个新的当前包缓冲区
			if (currentPacket == null) {
				currentPacket = new byte[readInc];
				// 读取第一个字节 判断类型
				currentPacketType = readByte();
			}

			// 解析响应数据包内容
			RedisPacket responsePacket = null;
			if (currentPacketType == REDIS_PROTOCOL_SIMPLE_STRING) {
				responsePacket = processSimpleStringPacket();
			} else if (currentPacketType == REDIS_PROTOCOL_BULK_STRINGS) {
				responsePacket = processBulkStringsPacket();
			} else if (currentPacketType == REDIS_PROTOCOL_ARRAY) {
				responsePacket = processArrayPacket();
			} else if (currentPacketType == REDIS_PROTOCOL_INTEGERS) {
				responsePacket = processIntegerPacket();
			} else if (currentPacketType == REDIS_PROTOCOL_ERRORS) {
				responsePacket = processErrorPacket();
			} else {
				// FIXME 需要确认发现有时数据包会出现多余的10字节 需要检查redis源码是不是这个问题
				if (currentPacketType != REDIS_PROTOCOL_LF) {
					throw new RedisProtocolException("未知的数据包类型：" + currentPacketType);
				} else {
					currentPacket = null;
				}
			}
			// 如果存在响应包 则执行清理各种标识位操作
			if (responsePacket != null) {
				clean();

				if (this.arrayPacket != null) {
					arrayPacket.addPacket(responsePacket);
					if (arrayPacket.getPackets().size() != arrayLength) {
						return null;
					} else {
						arrayLength = -2;
						arrayCrLfReaded = false;
						responsePacket = arrayPacket;
						arrayPacket = null;
						return responsePacket;
					}
				}
			}

			return responsePacket;
		} catch (Exception e) {
			throw new RedisProtocolException(e);
		}
	}

	/**
	 * 清理各种标识位的方法
	 * 
	 * @throws RedisProtocolException
	 */
	private void clean() throws RedisProtocolException {
		// 清空当前处理的数据包
		cleanCurrentPacket();
		// 清空当前数据包类型
		this.currentPacketType = 0;
		// 清空当前读取的数据包长度 bulk专用
		this.readedBulkLength = 0;
		// 清空当前数据包长度 bulk专用
		this.bulkLength = -2;
		// 还原bulk数据包长度正负标识位
		this.bulkNeg = 0;
		// 清空crlf标识位
		this.bulkCrLfReaded = false;

		// 如果数组类型数据包读取完毕 则清空对应的标识位
		if (arrayPacket != null && arrayPacket.getPackets().size() == arrayLength) {
			arrayLength = -2;
			arrayPacket = null;
			arrayCrLfReaded = false;
		}
	}

	/**
	 * 清理临时文件信息的方法
	 * 
	 * @throws RedisProtocolException
	 */
	private void cleanTempFile() throws RedisProtocolException {
		try {
			this.tempFileChannel.close();
			this.tempRandomAccessFile.close();
			this.tempFile = null;
		} catch (IOException e) {
			throw new RedisProtocolException(e);
		}
	}

	/**
	 * 清空当前处理中的数据包的方法
	 */
	private void cleanCurrentPacket() {
		currentPacket = null;
		currentPacketWriteFlag = 0;
	}

	/**
	 * 读取字符串类型数据包的方法
	 * 
	 * @return 字符串类型数据包
	 */
	private RedisPacket processSimpleStringPacket() throws RedisProtocolException {
		// 读取数据
		readData();
		// 获取完整数据包
		byte[] packetData = completCurrentPacket();
		if (packetData == null) {
			return null;
		}
		// 转换为数据包对象
		RedisSimpleStringPacket simpleStringPacket = new RedisSimpleStringPacket(new String(packetData), packetData);
		return simpleStringPacket;
	}

	/**
	 * 读取复合类型Redis字符串响应的方法
	 * 
	 * @return 复合类型字符串响应数据包
	 */
	private RedisPacket processBulkStringsPacket() throws RedisProtocolException {
		// 读取复合类型字符串数据长度
		long result = readBulkStringLength();
		if (result == -2) {
			return null;
		}

		// 如果是空长度的数据 说明一定不是数据传输包 直接返回空字符串
		if (result == 0) {
			return readBulkStringPacket();
		}

		// 读取bulk字符串内容
		readBulkStringContent();

		// 校验是否是数据传输包

		if (!isDatabaseTranceferChecked) {
			checkIsDatabaseTranceferPacket();
			// 如果是数据传输则创建临时文件
			if (this.isDatabaseTrancefer) {
				createTempFileChannel();

				// 写入当前已经读取的内容
				ByteBuffer buffer = createByteBuffer(currentPacketWriteFlag);
				buffer.put(currentPacket, 0, currentPacketWriteFlag);
				buffer.flip();

				while (buffer.hasRemaining()) {
					try {
						tempFileChannel.write(buffer);
					} catch (IOException e) {
						throw new RedisProtocolException(e);
					}
				}
			}
		}

		// 未读取完 直接返回 null
		if (readedBulkLength != bulkLength) {
			return null;
		}

		// 将数据转换为数据传输请求包
		if (isDatabaseTrancefer) {
			// 这就是完整的包了
			RedisDataBaseTransferPacket packet = null;
			try {
				packet = new RedisDataBaseTransferPacket(DATABASETRANSFER, tempFile);
			} catch (RedisRDBException e) {
				throw new RedisProtocolException(e);
			}
			return packet;
		} else {
			return readBulkStringPacket();
		}
	}

	/**
	 * 读取bulk字符串数据内容的方法
	 * 
	 * @throws RedisProtocolException
	 *             bulk字符串数据内容
	 */
	private void readBulkStringContent() throws RedisProtocolException {
		// 只有在有数据的时候读取数据
		while (readedBulkLength != bulkLength) {
			// 判断数据是否读取完了
			if (!hasRemaining()) {
				break;
			}
			byte b = readByte();
			readedBulkLength++;
			appendToCurrentPacket(b);
			if (readedBulkLength == bulkLength) {
				// 数据读取完了
				break;
			}
		}
	}

	/**
	 * 检查是否是数据传输包的方法
	 * 
	 * @throws RedisProtocolException
	 *             当出现问题时抛出该异常
	 */
	private void checkIsDatabaseTranceferPacket() throws RedisProtocolException {
		// 读取完了 判断长度是否大于5 如果大于5则读取前五个字节的数据
		if (readedBulkLength > 5) {
			byte b1 = currentPacket[0];
			byte b2 = currentPacket[1];
			byte b3 = currentPacket[2];
			byte b4 = currentPacket[3];
			byte b5 = currentPacket[4];
			if (b1 == 'R' && b2 == 'E' && b3 == 'D' && b4 == 'I' && b5 == 'S') {
				this.isDatabaseTrancefer = true;
			} else {
				this.isDatabaseTrancefer = false;
			}
			isDatabaseTranceferChecked = true;
		}

	}

	/**
	 * 读取普通bulk复合字符串类型数据的方法
	 * 
	 * @return 普通bulk复合字符串类型数据包对象
	 * @throws RedisProtocolException
	 *             当读取出现错误时抛出该异常
	 */
	private RedisPacket readBulkStringPacket() throws RedisProtocolException {
		// 读取bulk字符串额外的\r\n
		readData();
		// 获取完整数据包
		byte[] packetData = completCurrentPacket();
		if (packetData == null) {
			return null;
		}
		RedisBulkStringPacket packet = new RedisBulkStringPacket(BULKSTRING);
		packet.setData(packetData);
		return packet;
	}

	/**
	 * 读取redis数组格式数据的方法<br>
	 * 首先先读取数组内容的长度 再根据长度读取对应的数据
	 * 
	 * @return redis数组类型数据包
	 * @throws RedisProtocolException
	 *             当解析过程中发生错误则抛出该异常
	 */
	private RedisPacket processArrayPacket() throws RedisProtocolException {
		// 读取数组内容长度
		long arrayLength = readArrayLength();
		if (arrayLength == -2) {
			return null;
		}
		// 长度为0的空数组 直接返回
		if (arrayLength == 0) {
			arrayPacket = new RedisArraysPacket(ARRAY);
			return arrayPacket;
		}

		// 处理分包情况
		if (arrayPacket == null) {
			// 清空当前处理的数据包
			cleanCurrentPacket();
			// 清空当前数据包类型
			this.currentPacketType = 0;
			// 响应数据包
			this.arrayPacket = new RedisArraysPacket(ARRAY);
			arrayPacket.setArrayLength(arrayLength);
		}

		// 根据数组长度解析对应的数据包
		while (arrayPacket.getPackets().size() != arrayLength) {
			RedisPacket arrayPacket = decodePacket();
			if (arrayPacket != null) {
				return arrayPacket;
			} else {
				break;
			}
		}

		// 元素未读取完 返回空包
		if (arrayPacket.getPackets().size() != arrayLength) {
			// 清空当前数据包内容 等待读取元素
			clean();
			return null;
		}
		return arrayPacket;
	}

	/**
	 * 读取整数类型数据包的方法<br>
	 * 读取的数据为long形数据
	 * 
	 * @return redis数据包对象
	 */
	private RedisPacket processIntegerPacket() throws RedisProtocolException {
		// 读取数据
		readData();
		// 获取完整数据包
		byte[] packetData = completCurrentPacket();
		if (packetData == null) {
			return null;
		}
		long result = Long.parseLong(new String(packetData));
		RedisIntegerPacket integerPacket = new RedisIntegerPacket(INTEGER, packetData);
		integerPacket.setNum(result);
		return integerPacket;
	}

	/**
	 * 读取redis复合类型字符串长度的方法
	 * 
	 * @return 复合类型字符串长度
	 */
	private long readBulkStringLength() throws RedisProtocolException {
		// 判断是否读取过长度信息
		if (!bulkCrLfReaded) {
			// 判断是否读取过数据的正负符号
			if (bulkNeg == 0) {
				if (!hasRemaining()) {
					return -2;
				}
				byte isNegByte = readByte();
				boolean isNeg = isNegByte == '-';
				if (isNeg) {
					bulkNeg = -1;
				} else {
					appendToCurrentPacket(isNegByte);
					bulkNeg = 1;
				}
			}
			// 读取数据
			readData();
			// 获取完整数据包
			byte[] packetData = completCurrentPacket();
			if (packetData == null) {
				return -2;
			}
			// 解析数据长度
			bulkLength = 0;
			for (int i = 0; i < packetData.length; i++) {
				bulkLength = bulkLength * 10 + packetData[i] - '0';
			}
			bulkCrLfReaded = true;
			// 清空长度数据包读取信息
			cleanCurrentPacket();
			currentPacket = new byte[readInc];
		}
		return bulkLength;
	}

	/**
	 * 读取redis数组类型长度的方法
	 * 
	 * @return 数组类型长
	 */
	private long readArrayLength() throws RedisProtocolException {
		// 判断是否读取过长度信息
		if (!arrayCrLfReaded) {
			if (!hasRemaining()) {
				return -2;
			}
			// 读取数据
			readData();
			// 获取完整数据包
			byte[] packetData = completCurrentPacket();
			if (packetData == null) {
				return -2;
			}
			// 解析数据长度
			arrayLength = 0;
			for (int i = 0; i < packetData.length; i++) {
				arrayLength = arrayLength * 10 + packetData[i] - '0';
			}
			arrayCrLfReaded = true;
			// 清空长度数据包读取信息
			cleanCurrentPacket();
			currentPacket = new byte[readInc];
		}
		return arrayLength;
	}

	/**
	 * 读取错误类型数据包的方法
	 * 
	 * @return 错误类型数据包
	 */
	private RedisPacket processErrorPacket() throws RedisProtocolException {
		// 读取数据
		readData();
		// 获取完整数据包
		byte[] packetData = completCurrentPacket();
		if (packetData == null) {
			return null;
		}
		RedisErrorPacket errorPacket = new RedisErrorPacket(new String(ERR), packetData);
		errorPacket.setErrorMessage(new String(packetData));
		return errorPacket;
	}

	/**
	 * 读取一行数据的方法<br>
	 * 以\r\n为结尾 数据读取到currentPacket中
	 */
	private void readData() throws RedisProtocolException {
		while (true) {
			// 判断数据是否读取完了
			if (!hasRemaining()) {
				// 数据读完了 但是还没有数据包
				return;
			}
			// 读取一个字节
			byte b = readByte();
			// 追加数据到缓冲区
			appendToCurrentPacket(b);

			// 当数据为\n时 判断前当前读取到的数据包中倒数第二个字符是否为\r
			// 如果为\r则说明这是一个完整的包 不需要再读取数据了
			if (b == REDIS_PROTOCOL_LF) {
				if (currentPacketWriteFlag > 1 && currentPacket[currentPacketWriteFlag - 2] == REDIS_PROTOCOL_CR) {
					// 一个完整的包
					break;
				}
			}
		}
	}

	/**
	 * 将当前数据包处理缓冲区数据清除多余空间的方法
	 * 
	 * @return 当前数据包的数据
	 */
	private byte[] completCurrentPacket() {
		// 如果当前包中的数据长度小于2 则说明没读取完
		if (currentPacketWriteFlag < 2) {
			return null;
		}
		// 判断当钱包最后两位数据是否是\r\n 如果是则去掉最后两位并且返回一个完整的包
		if (currentPacket[currentPacketWriteFlag - 2] == REDIS_PROTOCOL_CR
				&& currentPacket[currentPacketWriteFlag - 1] == REDIS_PROTOCOL_LF) {
			byte[] result = new byte[currentPacketWriteFlag - 2];
			System.arraycopy(currentPacket, 0, result, 0, currentPacketWriteFlag - 2);
			return result;
		}
		return null;
	}

	/**
	 * 将数据追加到当前处理的数据包的方法<br>
	 * 自动检测缓冲区容量 判断是否需要扩容
	 * 
	 * @param b
	 *            需要追加的数据
	 * @throws RedisProtocolException
	 *             当写入过程中出现问题则抛出该异常
	 */
	private void appendToCurrentPacket(final byte b) throws RedisProtocolException {
		if (this.isDatabaseTrancefer) {
			ByteBuffer buffer = createByteBuffer(1);
			buffer.put(b);
			buffer.flip();
			while (buffer.hasRemaining()) {
				try {
					tempFileChannel.write(buffer);
				} catch (IOException e) {
					throw new RedisProtocolException(e);
				}
			}
		} else {
			if (currentPacketWriteFlag == currentPacket.length) {
				// 扩容
				byte[] newBuffer = new byte[currentPacketWriteFlag + readInc];
				System.arraycopy(currentPacket, 0, newBuffer, 0, currentPacketWriteFlag);
				currentPacket = newBuffer;
			}
			currentPacket[currentPacketWriteFlag++] = b;
		}
	}

	/**
	 * 创建临时文件管道对象的方法
	 * 
	 * @throws RedisProtocolException
	 *             当发生问题时抛出该异常
	 */
	private void createTempFileChannel() throws RedisProtocolException {
		try {
			tempFile = new File(FileUtils.getTempDirectory(), System.currentTimeMillis() + ".synyed");
			tempRandomAccessFile = new RandomAccessFile(tempFile, "rw");
			this.tempFileChannel = tempRandomAccessFile.getChannel();
		} catch (FileNotFoundException e) {
			throw new RedisProtocolException(e);
		}
	}

	/**
	 * 创建bytebuffer空数据对象的方法
	 * 
	 * @param size
	 *            bytebuffer空间
	 * @return 创建的bytebuffer对象
	 */
	private ByteBuffer createByteBuffer(int size) {
		ByteBuffer buf = ByteBuffer.allocate(size);
		buf.clear();
		return buf;
	}

	/**
	 * 从缓冲区读取一个字节的方法<br>
	 * 
	 * @return 读取到的字节数据
	 * @throws RedisProtocolException
	 *             当没有数据或者读取错误时抛出该异常
	 */
	private byte readByte() throws RedisProtocolException {
		// 判断数据是否读取完了
		if (!hasRemaining()) {
			throw new RedisProtocolException("EOF");
		}
		// 判断是否能够一次性读取完
		if ((limit - readFlag) >= 1) {
			return buffer[readFlag++];
		} else if (readFlag > limit && (maxLength - readFlag) + limit >= 1) {
			// 判断读取标志位后的数据是否够读
			if (maxLength - readFlag >= 1) {
				return buffer[readFlag++];
			} else {
				readFlag = 0;
				return buffer[readFlag++];
			}
		} else {
			throw new RedisProtocolException("EOF");
		}
	}

	/**
	 * 判断当前缓冲区中是否还有数据的方法<br>
	 * 
	 * @return true为有数 false为没数据
	 */
	private boolean hasRemaining() {
		if ((limit - readFlag) >= 1) {
			return true;
		} else if (readFlag > limit && (maxLength - readFlag) + limit >= 1) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * 获取解析到的消息内容列表的方法
	 * 
	 * @return 消息内容列表
	 */
	public RedisPacket[] getPackets() {
		// 如果没解析出消息啧返回空数组
		if (packets.size() == 0) {
			return null;
		}
		RedisPacket[] redisPackets = packets.toArray(new RedisPacket[packets.size()]);

		// 执行内存回收操作
		gc();

		return redisPackets;
	}

	/**
	 * 当缓冲区超过默认大小时进行内存回收<br>
	 * 同时对缓冲区内的数据进行整理
	 */
	private void gc() {
		// 清空当前数据包集合
		packets.clear();
		// 判断缓冲区大小是否为默认大小
		if (maxLength == BUFFERSIZE) {
			return;
		}
		if (readFlag == maxLength && !isFull) {
			buffer = null; // 释放缓冲区内存
			buffer = new byte[BUFFERSIZE];
			limit = 0;
			readFlag = 0;
			maxLength = BUFFERSIZE;
		}
	}
}
